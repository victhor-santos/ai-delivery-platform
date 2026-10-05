"""Select an offline segment model using temporally separated simulated deliveries."""

import argparse
import json
from pathlib import Path

from app.ml.artifacts import ObservationModelMetadata
from app.routing.graph import RoadGraph, load_graph
from app.routing.provenance import graph_checksum
from training.experiment import train_candidates
from training.model_bundle import write_model_bundle
from training.observation_dataset import LoadedObservationDataset, load_observation_dataset


def validate_observation_graph(data: LoadedObservationDataset, graph: RoadGraph) -> None:
    if graph.graph_version != data.manifest["graph_version"]:
        raise ValueError("Observation graph version differs from the supplied graph.")
    segments = graph.segments_by_id
    for rows in data.split.subsets().values():
        for row in rows:
            segment = segments.get(row.segment_id)
            if segment is None or any(
                getattr(row, attribute) != getattr(segment, attribute)
                for attribute in (
                    "from_node",
                    "to_node",
                    "distance_km",
                    "road_type",
                    "reference_speed_kmh",
                )
            ):
                raise ValueError("Observed segment attributes differ from the supplied graph.")


def train_observation_dataset(
    data: LoadedObservationDataset, output: Path, graph: RoadGraph, seed: int = 42
) -> ObservationModelMetadata:
    if output.exists():
        raise ValueError("Artifact output already exists; choose a new directory.")
    if any(not getattr(data.split, name) for name in ("train", "validation", "test")):
        raise ValueError("Training, validation and reserved test partitions must be nonempty.")
    validate_observation_graph(data, graph)
    result = train_candidates(data.split.train, data.split.validation, seed)
    report = result.report | {
        "report_version": ObservationModelMetadata.VALIDATION_REPORT_VERSION,
        "dataset_id": data.dataset_id,
        "dataset_manifest_sha256": data.manifest_sha256,
        "dataset_manifest_version": "delivery-observation-dataset-v1",
        "sample_schema_version": "delivery-segment-observation-v1",
        "data_origin": "simulated",
        "prediction_data_origin": "synthetic",
        "graph_version": graph.graph_version,
        "graph_sha256": graph_checksum(graph),
        "split_policy": data.manifest["split_policy"],
        "train_deliveries": len({row.delivery_id for row in data.split.train}),
        "validation_deliveries": len({row.delivery_id for row in data.split.validation}),
        "limitations": [
            "Simulated outcomes do not establish real-world travel time accuracy.",
            "Samples describe chosen paths and may cover only part of a route.",
            "The supplied graph matches observed segments; historical maps are not authenticated.",
            "Nonempty partitions do not guarantee representative coverage or sample size.",
            "This offline bundle is not supported by the current routing API.",
        ],
    }
    return write_model_bundle(
        result,
        data.split.validation,
        report,
        output,
        ObservationModelMetadata,
        {
            "dataset_id": data.dataset_id,
            "dataset_manifest_sha256": data.manifest_sha256,
            "graph_version": graph.graph_version,
            "graph_sha256": graph_checksum(graph),
            "seed": seed,
        },
    )


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Train an offline model from simulated deliveries."
    )
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument(
        "--graph", type=Path, required=True, help="Graph matching the observed segments."
    )
    parser.add_argument(
        "--output", type=Path, required=True, help="New offline artifact directory."
    )
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)
    try:
        metadata = train_observation_dataset(
            load_observation_dataset(args.dataset), args.output, load_graph(args.graph), args.seed
        )
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps(metadata.model_dump(mode="json"), indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
