"""Evaluate a saved offline model on reserved observations, without fitting it again."""

import argparse
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

from app.ml.artifacts import load_observation_model
from app.ml.pipelines import feature_matrix
from app.routing.graph import RoadGraph, load_graph
from app.routing.provenance import graph_checksum
from training.experiment import batch_latency
from training.metrics import evaluate_predictions
from training.observation_dataset import LoadedObservationDataset, load_observation_dataset
from training.serialization import json_bytes
from training.train_observations import validate_observation_graph


def evaluate_observation_model(
    data: LoadedObservationDataset, artifact: Path, graph: RoadGraph
) -> dict:
    model, metadata = load_observation_model(artifact)
    if (
        metadata.dataset_id != data.dataset_id
        or metadata.dataset_manifest_sha256 != data.manifest_sha256
    ):
        raise ValueError("Evaluation dataset differs from the artifact's original dataset.")
    if metadata.graph_version != graph.graph_version or metadata.graph_sha256 != graph_checksum(
        graph
    ):
        raise ValueError("Evaluation graph differs from the artifact's original graph.")
    validate_observation_graph(data, graph)
    samples = data.split.test
    if not samples:
        raise ValueError("Reserved test partition must be nonempty.")
    matrix = feature_matrix(samples)
    try:
        with np.errstate(over="raise", invalid="raise", divide="raise"):
            predictions = model.predict(matrix)
            latency = batch_latency(model, matrix)
    except (FloatingPointError, OverflowError) as exc:
        raise ValueError("Prediction exceeded the supported numeric range.") from exc
    model_metrics = evaluate_predictions(samples, predictions)
    reference = np.array([row.distance_km / row.reference_speed_kmh * 60 for row in samples])
    historical = np.array([row.predicted_travel_time_minutes for row in samples])
    historical_groups = defaultdict(list)
    for row in samples:
        historical_groups[row.model_version].append(row)
    return {
        "report_version": "delivery-observation-test-report-v1",
        "dataset_id": data.dataset_id,
        "dataset_manifest_sha256": data.manifest_sha256,
        "dataset_manifest_version": metadata.dataset_manifest_version,
        "sample_schema_version": metadata.sample_schema_version,
        "graph_version": graph.graph_version,
        "graph_sha256": metadata.graph_sha256,
        "model_version": metadata.model_version,
        "selected_candidate": metadata.selected_candidate,
        "partition": "test",
        "data_origin": "simulated",
        "prediction_data_origin": "synthetic",
        "selection_used_test": False,
        "model_refitted": False,
        "historical_predictions_recomputed": False,
        "valid_predictions": model_metrics["overall"]["invalid_predictions"] == 0,
        "test_rows": len(samples),
        "test_deliveries": len({row.delivery_id for row in samples}),
        "selected_model": model_metrics,
        "physical_reference": evaluate_predictions(samples, reference),
        "stored_predictions": evaluate_predictions(samples, historical),
        "historical_models": {
            name: evaluate_predictions(
                rows, np.array([row.predicted_travel_time_minutes for row in rows])
            )
            for name, rows in sorted(historical_groups.items())
        },
        "test_batch_latency": latency,
        "limitations": [
            "Simulated outcomes do not establish real-world travel time accuracy.",
            "Chosen paths do not provide times for unobserved edges or complete routes.",
            "Historical models may describe different trips and are not a controlled comparison.",
            "Nonempty partitions do not guarantee representative coverage or sample size.",
            "This report does not promote the offline model to the routing API.",
        ],
    }


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Evaluate the offline winner on reserved deliveries."
    )
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New test report JSON file.")
    args = parser.parse_args(argv)
    try:
        if args.output.exists():
            raise ValueError("Evaluation output already exists; choose a new report file.")
        report = evaluate_observation_model(
            load_observation_dataset(args.dataset), args.artifact, load_graph(args.graph)
        )
        with args.output.open("xb") as target:
            target.write(json_bytes(report))
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
