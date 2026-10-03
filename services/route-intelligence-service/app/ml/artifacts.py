import hashlib
import io
import json
import platform
import warnings
from importlib.metadata import version
from pathlib import Path
from pickle import UnpicklingError
from typing import Annotated, Literal, Self

import joblib
from pydantic import BaseModel, ConfigDict, Field, model_validator
from sklearn.pipeline import Pipeline

from app.ml.features import FEATURE_COLUMNS
from app.routing.graph import Identifier

MODEL_FILE = "segment_travel_time_model.joblib"
MAX_MODEL_BYTES = 64 * 1024 * 1024
Checksum = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
CandidateName = Literal["physical_reference", "dummy_median", "linear_regression", "random_forest"]


def runtime_versions() -> dict[str, str]:
    return {
        "python": platform.python_version(),
        "system": platform.system(),
        "machine": platform.machine(),
    } | {
        package: version(package)
        for package in (
            "route-intelligence-service",
            "numpy",
            "scipy",
            "scikit-learn",
            "joblib",
            "pydantic",
            "tzdata",
        )
    }


class ModelMetadata(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    artifact_schema_version: Literal["segment-model-artifact-v1"] = "segment-model-artifact-v1"
    model_version: Annotated[str, Field(pattern=r"^segment-model-v1-[a-f0-9]{16}$")]
    selected_candidate: CandidateName
    dataset_id: Checksum
    dataset_manifest_sha256: Checksum
    graph_version: Identifier
    graph_sha256: Checksum
    feature_schema_version: Literal["segment-features-v1"] = "segment-features-v1"
    feature_columns: tuple[str, ...] = FEATURE_COLUMNS
    sample_schema_version: Literal["segment-sample-v1"] = "segment-sample-v1"
    target: Literal["actual_travel_time_minutes"] = "actual_travel_time_minutes"
    data_origin: Literal["synthetic"] = "synthetic"
    seed: Annotated[int, Field(strict=True, ge=0, lt=2**32)]
    fit_partition: Literal["train"] = "train"
    selection_partition: Literal["validation"] = "validation"
    environment: dict[str, str]
    artifact_file: Literal["segment_travel_time_model.joblib"] = MODEL_FILE
    artifact_sha256: Checksum
    artifact_bytes: Annotated[int, Field(strict=True, gt=0, le=MAX_MODEL_BYTES)]
    validation_report_sha256: Checksum

    @model_validator(mode="after")
    def validate_identity(self) -> Self:
        if self.feature_columns != FEATURE_COLUMNS:
            raise ValueError("Artifact feature order is incompatible with this service.")
        if self.model_version != f"segment-model-v1-{self.artifact_sha256[:16]}":
            raise ValueError("Model version differs from the artifact checksum.")
        return self


def _bounded_bytes(path: Path, limit: int) -> bytes:
    with path.open("rb") as source:
        value = source.read(limit + 1)
    if len(value) > limit:
        raise ValueError(f"Artifact file exceeds its supported size: {path.name}")
    return value


def load_model(directory: Path) -> tuple[Pipeline, ModelMetadata]:
    """Load a locally trusted joblib bundle. Checksums verify integrity, not trust."""
    try:
        metadata = ModelMetadata.model_validate_json(
            _bounded_bytes(directory / "metadata.json", 2 * 1024 * 1024)
        )
        if metadata.environment != runtime_versions():
            raise ValueError("Artifact runtime/dependency versions are incompatible.")
        report = _bounded_bytes(directory / "validation-report.json", 2 * 1024 * 1024)
        if hashlib.sha256(report).hexdigest() != metadata.validation_report_sha256:
            raise ValueError("Validation report checksum differs from the artifact metadata.")
        content = _bounded_bytes(directory / MODEL_FILE, metadata.artifact_bytes)
        if (
            len(content) != metadata.artifact_bytes
            or hashlib.sha256(content).hexdigest() != metadata.artifact_sha256
        ):
            raise ValueError("Model checksum differs from the artifact metadata.")
        with warnings.catch_warnings():
            warnings.simplefilter("error")
            try:
                model = joblib.load(io.BytesIO(content))
            except Exception as exc:
                raise ValueError("Saved pipeline could not be deserialized.") from exc
        if not isinstance(model, Pipeline) or model.n_features_in_ != len(FEATURE_COLUMNS):
            raise ValueError(
                "Artifact must contain a fitted pipeline with the expected feature count."
            )
        validation = json.loads(report)
        selection = validation["selection"]
        candidate = validation["candidates"][metadata.selected_candidate]
        estimator = model.named_steps["regressor"]
        if (
            selection["selected"] != metadata.selected_candidate
            or selection["partition"] != metadata.selection_partition
            or selection["refit_on_validation"] is not False
            or selection["test_used_for_selection"] is not False
            or validation["dataset_id"] != metadata.dataset_id
            or validation["dataset_manifest_sha256"] != metadata.dataset_manifest_sha256
            or validation["seed"] != metadata.seed
            or candidate["estimator"] != type(estimator).__name__
            or candidate["parameters"] != estimator.get_params(deep=False)
        ):
            raise ValueError("Model provenance differs from the validation report.")
        return model, metadata
    except (
        OSError,
        ValueError,
        KeyError,
        TypeError,
        EOFError,
        UnpicklingError,
        ImportError,
        AttributeError,
        Warning,
    ) as exc:
        raise ValueError(f"Unable to load model artifact: {exc}") from exc
