"""Decide whether an offline observation model may replace the production routing model."""

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timedelta
from itertools import product
from pathlib import Path
from typing import Annotated

import numpy as np
from pydantic import BaseModel, ConfigDict, Field

from app.ml.artifacts import (
    MODEL_FILE,
    PROMOTION_REPORT_FILE,
    PROMOTION_REPORT_VERSION,
    load_model,
    load_observation_model,
)
from app.ml.features import (
    TRAFFIC_LEVELS,
    SegmentFeatures,
    features_for_segment,
    graph_timezone,
)
from app.ml.pipelines import feature_matrix
from app.ml.predictor import SegmentTravelTimePredictor
from app.routing.graph import RoadGraph, load_graph
from app.routing.provenance import graph_checksum
from training.evaluate_observation_model import evaluate_observation_model
from training.metrics import evaluate_predictions
from training.observation_dataset import LoadedObservationDataset, load_observation_dataset
from training.serialization import json_bytes

Fraction = Annotated[float, Field(strict=True, ge=0, lt=1, allow_inf_nan=False)]
Count = Annotated[int, Field(strict=True, ge=1, le=100_000)]
GRID_BATCH = 1000


class PromotionThresholds(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    min_test_rows: Count = 500
    min_rows_per_segment: Count = 20
    min_rows_per_traffic_level: Count = 50
    min_relative_improvement: Fraction = 0.05
    max_slice_regression: Fraction = 0.05
    min_slice_rows: Count = 20


def _gate(name: str, passed: bool, detail: str) -> dict:
    return {"name": name, "passed": bool(passed), "detail": detail}


def _grid_predictions(predictor: SegmentTravelTimePredictor, graph: RoadGraph) -> int:
    """Predict every segment, traffic level, hour and weekday the API can request."""
    monday = datetime(2026, 8, 3, tzinfo=graph_timezone())
    records: list[SegmentFeatures] = [
        features_for_segment(segment, level, monday + timedelta(days=day, hours=hour))
        for segment, level, day, hour in product(
            graph.segments, TRAFFIC_LEVELS, range(7), range(24)
        )
    ]
    for start in range(0, len(records), GRID_BATCH):
        predictor.predict(records[start : start + GRID_BATCH])
    return len(records)


def _coverage_gates(data: LoadedObservationDataset, graph: RoadGraph, limits) -> list[dict]:
    test = data.split.test
    gates = [
        _gate(
            "test_rows",
            len(test) >= limits.min_test_rows,
            f"{len(test)} reserved observations (minimum {limits.min_test_rows}).",
        )
    ]
    per_segment = {segment.segment_id: 0 for segment in graph.segments}
    for row in test:
        per_segment[row.segment_id] += 1
    sparse = sorted(
        name for name, rows in per_segment.items() if rows < limits.min_rows_per_segment
    )
    gates.append(
        _gate(
            "segment_coverage",
            not sparse,
            f"Segments under {limits.min_rows_per_segment} reserved rows: {sparse or 'none'}.",
        )
    )
    per_level = {level: sum(row.traffic_level == level for row in test) for level in TRAFFIC_LEVELS}
    gates.append(
        _gate(
            "traffic_coverage",
            min(per_level.values()) >= limits.min_rows_per_traffic_level,
            f"Reserved rows per traffic level: {per_level} "
            f"(minimum {limits.min_rows_per_traffic_level}).",
        )
    )
    hours = {row.hour for row in test}
    weekdays = {row.day_of_week for row in test}
    gates.append(
        _gate(
            "time_coverage",
            len(hours) == 24 and len(weekdays) == 7,
            f"Reserved rows cover {len(hours)} of 24 hours and {len(weekdays)} of 7 weekdays.",
        )
    )
    return gates


def _metric_gates(candidate: dict, production: dict, reference: dict, limits) -> list[dict]:
    overall = candidate["overall"]
    production_mae = production["overall"]["mae_minutes"]
    gates = [
        _gate(
            "valid_predictions",
            overall["invalid_predictions"] == 0 and overall["mae_minutes"] is not None,
            f"{overall['invalid_predictions']} invalid candidate predictions on reserved rows.",
        )
    ]
    if overall["mae_minutes"] is None or production_mae is None:
        return gates + [_gate("improves_production", False, "MAE is unavailable.")]
    required = production_mae * (1 - limits.min_relative_improvement)
    gates.append(
        _gate(
            "improves_production",
            overall["mae_minutes"] <= required,
            f"Candidate MAE {overall['mae_minutes']:.4f} min; production {production_mae:.4f} "
            f"min; required at most {required:.4f} min.",
        )
    )
    reference_mae = reference["overall"]["mae_minutes"]
    gates.append(
        _gate(
            "beats_physical_reference",
            overall["mae_minutes"] < reference_mae,
            f"Candidate MAE {overall['mae_minutes']:.4f} min; reference {reference_mae:.4f} min.",
        )
    )
    regressions = []
    for dimension in ("road_type", "traffic_level"):
        for label, metrics in candidate["by_group"][dimension].items():
            baseline = production["by_group"][dimension][label]
            if metrics["rows"] < limits.min_slice_rows:
                continue
            if metrics["mae_minutes"] > baseline["mae_minutes"] * (1 + limits.max_slice_regression):
                regressions.append(f"{dimension}={label}")
    gates.append(
        _gate(
            "no_slice_regression",
            not regressions,
            f"Slices worse than production by more than {limits.max_slice_regression:.0%}: "
            f"{regressions or 'none'}.",
        )
    )
    return gates


def validate_promotion(
    candidate_path: Path,
    production_path: Path,
    data: LoadedObservationDataset,
    graph: RoadGraph,
    limits: PromotionThresholds,
) -> dict:
    candidate_model, candidate = load_observation_model(candidate_path)
    production_model, production = load_model(production_path)
    checksum = graph_checksum(graph)
    gates = [
        _gate(
            "graph_compatibility",
            (candidate.graph_version, candidate.graph_sha256)
            == (production.graph_version, production.graph_sha256)
            == (graph.graph_version, checksum),
            f"Candidate {candidate.graph_version}, production {production.graph_version}, "
            f"supplied {graph.graph_version}; checksums must match.",
        ),
        _gate(
            "feature_contract",
            (candidate.feature_schema_version, candidate.feature_columns)
            == (production.feature_schema_version, production.feature_columns),
            f"Candidate and production use {candidate.feature_schema_version}.",
        ),
    ]
    try:
        cells = _grid_predictions(SegmentTravelTimePredictor(candidate_model, candidate), graph)
        gates.append(
            _gate("runtime_predictions", True, f"{cells} feature combinations are positive.")
        )
    except ValueError as exc:
        gates.append(_gate("runtime_predictions", False, str(exc)))
    evaluation = evaluate_observation_model(data, candidate_path, graph)
    test = data.split.test
    with np.errstate(over="raise", invalid="raise", divide="raise"):
        production_predictions = production_model.predict(feature_matrix(test))
    production_metrics = evaluate_predictions(test, production_predictions)
    gates += _coverage_gates(data, graph, limits)
    gates += _metric_gates(
        evaluation["selected_model"], production_metrics, evaluation["physical_reference"], limits
    )
    approved = all(gate["passed"] for gate in gates)
    metadata_bytes = (candidate_path / "metadata.json").read_bytes()
    return {
        "report_version": PROMOTION_REPORT_VERSION,
        "decision": "approved" if approved else "rejected",
        "failed_gates": [gate["name"] for gate in gates if not gate["passed"]],
        "gates": gates,
        "thresholds": limits.model_dump(),
        "candidate": {
            "model_version": candidate.model_version,
            "selected_candidate": candidate.selected_candidate,
            "artifact_sha256": candidate.artifact_sha256,
            "metadata_sha256": hashlib.sha256(metadata_bytes).hexdigest(),
            "validation_report_sha256": candidate.validation_report_sha256,
            "data_origin": candidate.data_origin,
        },
        "production": {
            "model_version": production.model_version,
            "artifact_sha256": production.artifact_sha256,
            "data_origin": production.data_origin,
        },
        "dataset_id": data.dataset_id,
        "dataset_manifest_sha256": data.manifest_sha256,
        "graph_version": graph.graph_version,
        "graph_sha256": checksum,
        "test_rows": len(test),
        "test_deliveries": len({row.delivery_id for row in test}),
        "candidate_metrics": evaluation["selected_model"],
        "production_metrics": production_metrics,
        "physical_reference_metrics": evaluation["physical_reference"],
        "limitations": [
            "Simulated outcomes do not establish real-world travel time accuracy.",
            "Gates compare segment times on reserved rows, not complete routes.",
            "Approval allows serving the bundle; it does not configure or restart the API.",
        ],
    }


def promote(report: dict, candidate_path: Path, output: Path) -> None:
    """Write the decision to a new directory; only an approved decision carries the bundle."""
    output.mkdir(parents=True, exist_ok=False)
    if report["decision"] == "approved":
        for name in (MODEL_FILE, "metadata.json", "validation-report.json"):
            shutil.copyfile(candidate_path / name, output / name)
    (output / PROMOTION_REPORT_FILE).write_bytes(json_bytes(report))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Validate an observation model against the production model."
    )
    parser.add_argument("--candidate", type=Path, required=True, help="Observation bundle.")
    parser.add_argument(
        "--production-model", type=Path, required=True, help="Bundle served by the routing API."
    )
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New promotion directory.")
    args = parser.parse_args(argv)
    try:
        if args.output.exists():
            raise ValueError("Promotion output already exists; choose a new directory.")
        report = validate_promotion(
            args.candidate,
            args.production_model,
            load_observation_dataset(args.dataset),
            load_graph(args.graph),
            PromotionThresholds(),
        )
        promote(report, args.candidate, args.output)
    except (ValueError, OSError, FloatingPointError) as exc:
        parser.error(str(exc))
    print(
        json.dumps(
            {key: report[key] for key in ("decision", "failed_gates", "gates")},
            indent=2,
            allow_nan=False,
        )
    )
    if report["decision"] != "approved":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
