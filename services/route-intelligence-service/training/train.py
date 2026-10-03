import argparse
import hashlib
import json
from pathlib import Path

import joblib
import numpy as np

from app.ml.artifacts import MODEL_FILE, ModelMetadata, load_model, runtime_versions
from app.ml.pipelines import feature_matrix
from training.experiment import train_candidates
from training.load_dataset import LoadedDataset, load_dataset
from training.serialization import json_bytes


def train_dataset(data: LoadedDataset, output: Path, seed: int = 42) -> ModelMetadata:
    if output.exists():
        raise ValueError("Artifact output already exists; choose a new directory.")
    result = train_candidates(data.split.train, data.split.validation, seed)
    report = result.report | {
        "dataset_id": data.dataset_id,
        "dataset_manifest_sha256": data.manifest_sha256,
        "data_origin": "synthetic",
    }
    try:
        output.mkdir(parents=True, exist_ok=False)
        joblib.dump(result.model, output / MODEL_FILE, compress=3)
        content = (output / MODEL_FILE).read_bytes()
        checksum = hashlib.sha256(content).hexdigest()
        report_bytes = json_bytes(report)
        metadata = ModelMetadata(
            model_version=f"segment-model-v1-{checksum[:16]}",
            selected_candidate=result.selected_name,
            dataset_id=data.dataset_id,
            dataset_manifest_sha256=data.manifest_sha256,
            graph_version=data.manifest["graph"]["graph_version"],
            graph_sha256=data.manifest["graph"]["sha256"],
            seed=seed,
            environment=runtime_versions(),
            artifact_sha256=checksum,
            artifact_bytes=len(content),
            validation_report_sha256=hashlib.sha256(report_bytes).hexdigest(),
        )
        (output / "validation-report.json").write_bytes(report_bytes)
        restored = joblib.load(output / MODEL_FILE)
        matrix = feature_matrix(data.split.validation)
        if not np.allclose(
            restored.predict(matrix), result.model.predict(matrix), rtol=1e-12, atol=1e-12
        ):
            raise ValueError("Serialized model changed its validation predictions.")
        (output / "metadata.json").write_bytes(json_bytes(metadata.model_dump(mode="json")))
        load_model(output)
        return metadata
    except OSError as exc:
        raise ValueError(
            "Unable to write model bundle; do not consume an incomplete directory."
        ) from exc


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
