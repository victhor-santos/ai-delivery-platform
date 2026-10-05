import argparse
import json
from pathlib import Path

from app.ml.artifacts import ModelMetadata
from training.experiment import train_candidates
from training.load_dataset import LoadedDataset, load_dataset
from training.model_bundle import write_model_bundle


def train_dataset(data: LoadedDataset, output: Path, seed: int = 42) -> ModelMetadata:
    if output.exists():
        raise ValueError("Artifact output already exists; choose a new directory.")
    result = train_candidates(data.split.train, data.split.validation, seed)
    report = result.report | {
        "dataset_id": data.dataset_id,
        "dataset_manifest_sha256": data.manifest_sha256,
        "data_origin": "synthetic",
    }
    return write_model_bundle(
        result,
        data.split.validation,
        report,
        output,
        ModelMetadata,
        {
            "dataset_id": data.dataset_id,
            "dataset_manifest_sha256": data.manifest_sha256,
            "graph_version": data.manifest["graph"]["graph_version"],
            "graph_sha256": data.manifest["graph"]["sha256"],
            "seed": seed,
        },
    )


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Train candidates and select on validation only.")
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New artifact directory.")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)
    try:
        metadata = train_dataset(load_dataset(args.dataset), args.output, args.seed)
    except ValueError as exc:
        parser.error(str(exc))
    print(json.dumps(metadata.model_dump(mode="json"), indent=2))


if __name__ == "__main__":
    main()
