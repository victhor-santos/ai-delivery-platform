import argparse
import json
from pathlib import Path

import numpy as np

from app.ml.artifacts import load_model
from app.ml.pipelines import feature_matrix
from app.routing.demo import load_demo_graph
from app.routing.graph import RoadGraph, load_graph
from training.dataset import graph_checksum
from training.experiment import batch_latency
from training.load_dataset import LoadedDataset, load_dataset
from training.metrics import evaluate_predictions
from training.route_evaluation import evaluate_routes
from training.serialization import json_bytes


def evaluate_model(
    data: LoadedDataset,
    artifact: Path,
    graph: RoadGraph,
    origin: str = "A",
    destination: str = "C",
) -> dict:
    model, metadata = load_model(artifact)
    if (
        metadata.dataset_id != data.dataset_id
        or metadata.dataset_manifest_sha256 != data.manifest_sha256
    ):
        raise ValueError("Evaluation dataset differs from the artifact's original dataset.")
    if metadata.graph_version != graph.graph_version or metadata.graph_sha256 != graph_checksum(
        graph
    ):
        raise ValueError("Evaluation graph differs from the artifact's original graph.")
    samples = data.split.test
    matrix = feature_matrix(samples)
    predictions = model.predict(matrix)
    reference = np.array([s.distance_km / s.reference_speed_kmh * 60 for s in samples])
    model_metrics = evaluate_predictions(samples, predictions)
    return {
        "report_version": "segment-test-report-v1",
        "dataset_id": data.dataset_id,
        "dataset_manifest_sha256": data.manifest_sha256,
        "model_version": metadata.model_version,
        "selected_candidate": metadata.selected_candidate,
        "partition": "test",
        "data_origin": "synthetic",
        "selection_used_test": False,
        "model_refitted": False,
        "valid_predictions": model_metrics["overall"]["invalid_predictions"] == 0,
        "selected_model": model_metrics,
        "physical_reference": evaluate_predictions(samples, reference),
        "test_batch_latency": batch_latency(model, matrix),
        "routing": {
            "selected_model": evaluate_routes(graph, samples, predictions, origin, destination),
            "physical_reference": evaluate_routes(graph, samples, reference, origin, destination),
        },
    }


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Evaluate the saved winner on reserved test data.")
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New JSON report file.")
    parser.add_argument("--graph", type=Path, help="Defaults to packaged synthetic-city-v1.")
    parser.add_argument("--origin", default="A")
    parser.add_argument("--destination", default="C")
    args = parser.parse_args(argv)
    try:
        if args.output.exists():
            raise ValueError("Evaluation output already exists; choose a new report file.")
        graph = load_graph(args.graph) if args.graph else load_demo_graph()
        report = evaluate_model(
            load_dataset(args.dataset), args.artifact, graph, args.origin, args.destination
        )
        with args.output.open("xb") as target:
            target.write(json_bytes(report))
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
