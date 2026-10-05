"""Audit stored predictions against exported outcomes, without loading or fitting a model."""

import argparse
import json
from collections import defaultdict
from dataclasses import asdict
from datetime import datetime
from pathlib import Path

import numpy as np
from pydantic import TypeAdapter

from training.metrics import distance_band, regression_metrics
from training.observations import (
    DeliveryObservation,
    LoadedObservations,
    ObservationTimestamp,
    load_observations,
)
from training.serialization import json_bytes


def _metrics(rows: list[DeliveryObservation]) -> dict:
    return regression_metrics(
        np.array([row.actual_travel_time_minutes for row in rows]),
        np.array([row.predicted_travel_time_minutes for row in rows]),
    )


def evaluate_observations(data: LoadedObservations, available_at_cutoff: datetime) -> dict:
    cutoff = TypeAdapter(ObservationTimestamp).validate_python(available_at_cutoff)
    included = [row for row in data.rows if row.label_available_at <= cutoff]
    versions = defaultdict(list)
    for row in included:
        versions[(row.model_version, row.graph_version)].append(row)
    groups = []
    for (model_version, graph_version), rows in sorted(versions.items()):
        breakdown = {}
        for dimension in ("road_type", "traffic_level", "distance_band"):
            buckets = defaultdict(list)
            for row in rows:
                label = (
                    distance_band(row.distance_km)
                    if dimension == "distance_band"
                    else getattr(row, dimension)
                )
                buckets[label].append(row)
            breakdown[dimension] = {
                label: _metrics(items) for label, items in sorted(buckets.items())
            }
        groups.append(
            {
                "model_version": model_version,
                "graph_version": graph_version,
                "deliveries": len({row.delivery_id for row in rows}),
                "overall": _metrics(rows),
                "by_group": breakdown,
            }
        )
    return {
        "report_version": "delivery-observation-report-v1",
        "observation_set_id": data.observation_set_id,
        "available_at_cutoff": cutoff.isoformat(),
        "data_origin": "simulated",
        "prediction_data_origin": "synthetic",
        "model_refitted": False,
        "predictions_recomputed": False,
        "sources": [asdict(source) for source in data.sources],
        "input_rows": sum(source.rows for source in data.sources),
        "duplicate_rows": data.duplicate_rows,
        "unique_rows": len(data.rows),
        "included_rows": len(included),
        "excluded_after_cutoff": len(data.rows) - len(included),
        "included_deliveries": len({row.delivery_id for row in included}),
        "groups": groups,
        "limitations": [
            "Simulated outcomes do not establish real-world travel time accuracy.",
            "Exports may contain only part of a route; no complete-route metric is inferred.",
            "Model groups may describe different trips and are not a controlled comparison.",
            "This report does not create training partitions or retrain a model.",
        ],
    }


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Evaluate recorded predictions from Delivery CSVs."
    )
    parser.add_argument(
        "--input", type=Path, nargs="+", required=True, help="Delivery CSV exports."
    )
    parser.add_argument(
        "--available-at-cutoff", required=True, help="Inclusive UTC/offset timestamp."
    )
    parser.add_argument("--output", type=Path, required=True, help="New JSON report file.")
    args = parser.parse_args(argv)
    try:
        if args.output.exists():
            raise ValueError("Observation report already exists; choose a new output file.")
        cutoff = TypeAdapter(ObservationTimestamp).validate_python(args.available_at_cutoff)
        report = evaluate_observations(load_observations(args.input), cutoff)
        content = json_bytes(report)
        with args.output.open("xb") as target:
            target.write(content)
    except (ValueError, OSError, OverflowError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
