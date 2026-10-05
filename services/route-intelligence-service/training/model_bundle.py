"""Serialize and verify a selected pipeline for either supported offline data contract."""

import hashlib
from collections.abc import Sequence
from pathlib import Path

import joblib
import numpy as np

from app.ml.artifacts import MODEL_FILE, ModelMetadata, load_model_bundle, runtime_versions
from app.ml.features import SegmentFeatures
from app.ml.pipelines import feature_matrix
from training.experiment import TrainingResult
from training.serialization import json_bytes


def write_model_bundle[Metadata: ModelMetadata](
    result: TrainingResult,
    validation: Sequence[SegmentFeatures],
    report: dict,
    output: Path,
    metadata_type: type[Metadata],
    metadata_fields: dict,
) -> Metadata:
    if output.exists():
        raise ValueError("Artifact output already exists; choose a new directory.")
    try:
        output.mkdir(parents=True, exist_ok=False)
        joblib.dump(result.model, output / MODEL_FILE, compress=3)
        content = (output / MODEL_FILE).read_bytes()
        checksum = hashlib.sha256(content).hexdigest()
        report_bytes = json_bytes(report)
        metadata = metadata_type(
            model_version=f"{metadata_type.MODEL_VERSION_PREFIX}-{checksum[:16]}",
            selected_candidate=result.selected_name,
            **metadata_fields,
            environment=runtime_versions(),
            artifact_sha256=checksum,
            artifact_bytes=len(content),
            validation_report_sha256=hashlib.sha256(report_bytes).hexdigest(),
        )
        (output / "validation-report.json").write_bytes(report_bytes)
        restored = joblib.load(output / MODEL_FILE)
        matrix = feature_matrix(validation)
        if not np.allclose(
            restored.predict(matrix), result.model.predict(matrix), rtol=1e-12, atol=1e-12
        ):
            raise ValueError("Serialized model changed its validation predictions.")
        (output / "metadata.json").write_bytes(json_bytes(metadata.model_dump(mode="json")))
        load_model_bundle(output, metadata_type)
        return metadata
    except OSError as exc:
        raise ValueError(
            "Unable to write model bundle; do not consume an incomplete directory."
        ) from exc
