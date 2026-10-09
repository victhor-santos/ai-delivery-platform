"""Simulate Delivery exports covering every segment, hour, weekday and traffic level."""

import argparse
import csv
import json
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from random import Random
from typing import Annotated, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.ml.artifacts import load_model
from app.ml.features import TRAFFIC_LEVELS, departure_context, features_for_segment
from app.ml.predictor import SegmentTravelTimePredictor
from app.routing.dijkstra import reference_travel_times
from app.routing.graph import RoadGraph, load_graph
from app.routing.provenance import graph_checksum
from training.observations import MAX_OBSERVATIONS, OBSERVATION_COLUMNS, DeliveryObservation
from training.schema import UtcTimestamp
from training.splits import SplitPlan
from training.synthetic import TRAFFIC_FACTORS, time_factor

PREDICTION_BATCH = 1000


class SimulationConfig(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid", validate_default=True)

    seed: Annotated[int, Field(strict=True, ge=0, le=2**32 - 1)] = 42
    start_at: UtcTimestamp = datetime.fromisoformat("2026-08-03T00:00:00-03:00")
    train_days: Annotated[int, Field(strict=True, ge=1, le=120)] = 28
    validation_days: Annotated[int, Field(strict=True, ge=1, le=120)] = 7
    test_days: Annotated[int, Field(strict=True, ge=1, le=120)] = 7
    deliveries_per_day: Annotated[int, Field(strict=True, ge=1, le=1000)] = 96
    max_segments: Annotated[int, Field(strict=True, ge=1, le=20)] = 4
    noise_min: Annotated[float, Field(strict=True, gt=0, le=2, allow_inf_nan=False)] = 0.85
    noise_max: Annotated[float, Field(strict=True, gt=0, le=2, allow_inf_nan=False)] = 1.15
    high_traffic_shift: Annotated[float, Field(strict=True, ge=0.5, le=3, allow_inf_nan=False)] = (
        1.0
    )

    @model_validator(mode="after")
    def validate_period_and_noise(self) -> Self:
        if self.noise_min > self.noise_max:
            raise ValueError("Minimum noise must not exceed maximum noise.")
        if self.days * self.deliveries_per_day * self.max_segments > MAX_OBSERVATIONS:
            raise ValueError(f"The simulation may exceed {MAX_OBSERVATIONS} observations.")
        return self

    @property
    def days(self) -> int:
        return self.train_days + self.validation_days + self.test_days

    @property
    def split_plan(self) -> SplitPlan:
        train_end = self.start_at + timedelta(days=self.train_days)
        validation_end = train_end + timedelta(days=self.validation_days)
        return SplitPlan(
            start_at=self.start_at,
            train_end=train_end,
            validation_end=validation_end,
            test_end=validation_end + timedelta(days=self.test_days),
        )


def _identifier(random: Random) -> uuid.UUID:
    return uuid.UUID(int=random.getrandbits(128), version=4)


def _walk(graph: RoadGraph, random: Random, max_segments: int) -> list:
    outgoing = {}
    for segment in sorted(graph.segments, key=lambda segment: segment.segment_id):
        outgoing.setdefault(segment.from_node, []).append(segment)
    node = random.choice(sorted(outgoing))
    path = []
    for _ in range(random.randint(1, max_segments)):
        options = outgoing.get(node)
        if not options:
            break
        segment = random.choice(options)
        path.append(segment)
        node = segment.to_node
    return path


def simulate_observations(
    graph: RoadGraph, predictor: SegmentTravelTimePredictor, config: SimulationConfig
) -> tuple[DeliveryObservation, ...]:
    """Journeys explore the graph instead of following the chosen route, so every edge is seen.

    Durations follow the synthetic travel-time process, measured at the actual entry time, with an
    optional shift of heavy traffic. Stored predictions come from the supplied production model.
    """
    if (
        predictor.metadata.graph_version != graph.graph_version
        or predictor.metadata.graph_sha256 != graph_checksum(graph)
    ):
        raise ValueError("Production model and graph are incompatible.")
    if not graph.segments:
        raise ValueError("The graph has no segments to simulate.")
    baseline = reference_travel_times(graph)
    random = Random(config.seed)
    rows = []
    for day in range(config.days):
        for _ in range(config.deliveries_per_day):
            departure = config.start_at + timedelta(days=day, seconds=random.randrange(1, 86_400))
            prediction = departure - timedelta(minutes=1)
            delivery_id = _identifier(random)
            route_plan_id = _identifier(random)
            entered = departure + timedelta(seconds=random.randrange(0, 60))
            for sequence, segment in enumerate(_walk(graph, random, config.max_segments)):
                traffic_level = TRAFFIC_LEVELS[random.randrange(len(TRAFFIC_LEVELS))]
                features = features_for_segment(segment, traffic_level, departure)
                hour, weekday = departure_context(entered)
                shift = config.high_traffic_shift if traffic_level == "high" else 1.0
                noise = config.noise_min + (config.noise_max - config.noise_min) * random.random()
                duration = (
                    baseline[segment.segment_id]
                    * TRAFFIC_FACTORS[traffic_level]
                    * shift
                    * time_factor(hour, weekday)
                    * noise
                )
                exited = entered + timedelta(minutes=duration)
                recorded = exited + timedelta(seconds=2)
                rows.append(
                    {
                        **features.model_dump(),
                        "schema_version": "delivery-segment-observation-v1",
                        "traversal_id": _identifier(random),
                        "delivery_id": delivery_id,
                        "route_plan_id": route_plan_id,
                        "sequence": sequence,
                        "data_origin": "simulated",
                        "prediction_data_origin": predictor.metadata.data_origin,
                        "segment_id": segment.segment_id,
                        "from_node": segment.from_node,
                        "to_node": segment.to_node,
                        "graph_version": graph.graph_version,
                        "model_version": predictor.metadata.model_version,
                        "feature_schema_version": "segment-features-v1",
                        "timezone": graph.timezone,
                        "traffic_source": "synthetic-traffic-v1",
                        "traffic_observed_at": prediction - timedelta(minutes=1),
                        "traffic_available_at": prediction - timedelta(seconds=30),
                        "features_available_at": prediction - timedelta(seconds=30),
                        "prediction_at": prediction,
                        "planned_departure_at": departure,
                        "context_as_of": prediction,
                        "entered_at": entered,
                        "entry_recorded_at": entered + timedelta(seconds=1),
                        "exited_at": exited,
                        "recorded_at": recorded,
                        "label_available_at": recorded,
                        "actual_travel_time_minutes": (exited - entered).total_seconds() / 60,
                        "features": features,
                    }
                )
                entered = exited + timedelta(seconds=random.randrange(0, 30))
    predictions = []
    for start in range(0, len(rows), PREDICTION_BATCH):
        batch = rows[start : start + PREDICTION_BATCH]
        predictions.extend(predictor.predict([row["features"] for row in batch]))
    return tuple(
        DeliveryObservation.model_validate(
            {key: value for key, value in row.items() if key != "features"}
            | {"predicted_travel_time_minutes": predicted}
        )
        for row, predicted in zip(rows, predictions, strict=True)
    )


def write_observations(rows: tuple[DeliveryObservation, ...], output: Path) -> None:
    with output.open("x", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=OBSERVATION_COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(row.model_dump(mode="json") for row in rows)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Simulate Delivery observation exports for model promotion checks."
    )
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument(
        "--production-model", type=Path, required=True, help="Bundle served by the routing API."
    )
    parser.add_argument("--output", type=Path, required=True, help="New CSV export file.")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--deliveries-per-day", type=int, default=96)
    parser.add_argument(
        "--high-traffic-shift",
        type=float,
        default=1.0,
        help="Multiplier on heavy-traffic durations, simulating a change the model has not seen.",
    )
    args = parser.parse_args(argv)
    try:
        if args.output.exists():
            raise ValueError("Simulation output already exists; choose a new CSV file.")
        config = SimulationConfig(
            seed=args.seed,
            deliveries_per_day=args.deliveries_per_day,
            high_traffic_shift=args.high_traffic_shift,
        )
        graph = load_graph(args.graph)
        model, metadata = load_model(args.production_model)
        rows = simulate_observations(graph, SegmentTravelTimePredictor(model, metadata), config)
        write_observations(rows, args.output)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    plan = config.split_plan
    print(
        json.dumps(
            {
                "output": str(args.output),
                "rows": len(rows),
                "deliveries": len({row.delivery_id for row in rows}),
                "production_model_version": metadata.model_version,
                "split_plan": plan.model_dump(mode="json"),
            },
            indent=2,
            allow_nan=False,
        )
    )


if __name__ == "__main__":
    main()
