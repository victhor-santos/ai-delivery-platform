from datetime import datetime, timedelta
from random import Random
from types import MappingProxyType
from typing import Annotated, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.ml.features import TRAFFIC_LEVELS, features_for_segment
from app.routing.dijkstra import reference_travel_times
from app.routing.graph import RoadGraph
from training.schema import SegmentSample, UtcTimestamp

MAX_SAMPLES = 100_000
TRAFFIC_FACTORS = MappingProxyType({"low": 1.0, "medium": 1.5, "high": 2.2})
PEAK_HOURS = (7, 8, 9, 17, 18, 19)
PEAK_FACTOR = 1.35
NIGHT_FACTOR = 0.8
WEEKEND_FACTOR = 0.95


class GeneratorConfig(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid", validate_default=True)

    seed: Annotated[int, Field(strict=True, ge=0, le=2**32 - 1)] = 42
    start_at: UtcTimestamp = datetime.fromisoformat("2026-08-03T00:00:00-03:00")
    train_days: Annotated[int, Field(strict=True, ge=1, le=365)] = 28
    validation_days: Annotated[int, Field(strict=True, ge=1, le=365)] = 7
    test_days: Annotated[int, Field(strict=True, ge=1, le=365)] = 7
    interval_minutes: Annotated[int, Field(strict=True, ge=15, le=1440)] = 60
    noise_min: Annotated[float, Field(strict=True, gt=0, le=2, allow_inf_nan=False)] = 0.85
    noise_max: Annotated[float, Field(strict=True, gt=0, le=2, allow_inf_nan=False)] = 1.15
    recording_delay_seconds: Annotated[int, Field(strict=True, ge=0, le=86400)] = 30
    label_delay_seconds: Annotated[int, Field(strict=True, ge=0, le=86400)] = 30

    @model_validator(mode="after")
    def validate_period_and_noise(self) -> Self:
        days = self.train_days + self.validation_days + self.test_days
        if days > 365:
            raise ValueError("The simulation period must not exceed 365 days.")
        if 1440 % self.interval_minutes:
            raise ValueError("The interval must divide a full day exactly.")
        if self.noise_min > self.noise_max:
            raise ValueError("Minimum noise must not exceed maximum noise.")
        try:
            self.start_at - timedelta(minutes=1)
            self.start_at + timedelta(days=days)
        except OverflowError as exc:
            raise ValueError("The simulation period exceeds the supported calendar.") from exc
        return self

    @property
    def scenario_count(self) -> int:
        return (
            (self.train_days + self.validation_days + self.test_days)
            * 1440
            // self.interval_minutes
        )


def time_factor(hour: int, day_of_week: int) -> float:
    if hour < 6:
        return NIGHT_FACTOR
    if day_of_week < 5 and hour in PEAK_HOURS:
        return PEAK_FACTOR
    if day_of_week >= 5:
        return WEEKEND_FACTOR
    return 1.0


def generate_samples(graph: RoadGraph, config: GeneratorConfig) -> tuple[SegmentSample, ...]:
    count = config.scenario_count * len(graph.segments)
    if not count or count > MAX_SAMPLES:
        raise ValueError(f"The dataset must contain between 1 and {MAX_SAMPLES} samples.")
    segments = sorted(graph.segments, key=lambda segment: segment.segment_id)
    baseline = reference_travel_times(graph)
    random = Random(config.seed)
    samples = []
    for index in range(config.scenario_count):
        prediction = config.start_at + timedelta(minutes=index * config.interval_minutes)
        departure = prediction + timedelta(minutes=1)
        scenario_id = f"scenario-{index:06}"
        for segment in segments:
            traffic_level = TRAFFIC_LEVELS[int(random.random() * len(TRAFFIC_LEVELS))]
            features = features_for_segment(segment, traffic_level, departure)
            noise = config.noise_min + (config.noise_max - config.noise_min) * random.random()
            duration = (
                baseline[segment.segment_id]
                * TRAFFIC_FACTORS[traffic_level]
                * time_factor(features.hour, features.day_of_week)
                * noise
            )
            try:
                exited = departure + timedelta(minutes=duration)
                recorded = exited + timedelta(seconds=config.recording_delay_seconds)
                available = recorded + timedelta(seconds=config.label_delay_seconds)
            except (OverflowError, ValueError) as exc:
                raise ValueError(
                    "Generated traversal duration exceeds the supported calendar."
                ) from exc
            samples.append(
                SegmentSample(
                    **features.model_dump(),
                    scenario_id=scenario_id,
                    traversal_id=f"{scenario_id}.{segment.segment_id}",
                    segment_id=segment.segment_id,
                    from_node=segment.from_node,
                    to_node=segment.to_node,
                    graph_version=graph.graph_version,
                    prediction_at=prediction,
                    planned_departure_at=departure,
                    context_as_of=prediction,
                    traffic_observed_at=prediction - timedelta(minutes=1),
                    traffic_available_at=prediction - timedelta(seconds=30),
                    features_available_at=prediction - timedelta(seconds=30),
                    entered_at=departure,
                    exited_at=exited,
                    recorded_at=recorded,
                    label_available_at=available,
                    actual_travel_time_minutes=(exited - departure).total_seconds() / 60,
                )
            )
    return tuple(samples)
