import csv
import hashlib
import io
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.ml.features import FEATURE_COLUMNS, FEATURE_SCHEMA_VERSION, ROAD_TYPES, TRAFFIC_LEVELS
from app.routing.graph import Identifier
from training.schema import (
    GENERATOR_VERSION,
    SAMPLE_COLUMNS,
    SAMPLE_SCHEMA_VERSION,
    TARGET_COLUMN,
    SegmentSample,
)
from training.serialization import payload_checksum
from training.splits import DatasetSplit, SplitPlan, split_plan_for, split_samples
from training.synthetic import MAX_SAMPLES, GeneratorConfig

FILE_NAMES = ("samples.csv", "train.csv", "validation.csv", "test.csv", "excluded.csv")
MAX_CSV_BYTES = 140 * 1024 * 1024
SHA256 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class FileMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rows: Annotated[int, Field(strict=True, ge=0, le=MAX_SAMPLES)]
    bytes: Annotated[int, Field(strict=True, gt=0, le=MAX_CSV_BYTES)]
    sha256: SHA256


class GraphMetadata(BaseModel):
    graph_version: Identifier
    sha256: SHA256
    timezone: Literal["America/Sao_Paulo"]
    vehicle_profile: Literal["motorcycle"]


class DatasetManifest(BaseModel):
    manifest_version: Literal["segment-dataset-manifest-v1"]
    dataset_id: SHA256
    data_origin: Literal["synthetic"]
    generator_version: Literal["synthetic-segments-v1"]
    graph: GraphMetadata
    configuration: GeneratorConfig
    files: dict[str, FileMetadata]


@dataclass(frozen=True)
class LoadedDataset:
    manifest: dict
    manifest_sha256: str
    split: DatasetSplit

    @property
    def dataset_id(self) -> str:
        return self.manifest["dataset_id"]


def _read_bounded(path: Path, limit: int) -> bytes:
    with path.open("rb") as source:
        content = source.read(limit + 1)
    if len(content) > limit:
        raise ValueError(f"File exceeds its supported size: {path.name}")
    return content


def _read_samples(content: bytes, expected_rows: int) -> tuple[SegmentSample, ...]:
    reader = csv.DictReader(io.StringIO(content.decode("utf-8"), newline=""))
    if tuple(reader.fieldnames or ()) != SAMPLE_COLUMNS:
        raise ValueError("CSV columns must exactly match the versioned observation schema.")
    samples = []
    for row in reader:
        if len(samples) >= expected_rows or None in row or None in row.values():
            raise ValueError("CSV row count or shape differs from the manifest.")
        for column in ("distance_km", "reference_speed_kmh", TARGET_COLUMN):
            row[column] = float(row[column])
        for column in ("hour", "day_of_week"):
            row[column] = int(row[column])
        samples.append(SegmentSample.model_validate(row))
    if len(samples) != expected_rows:
        raise ValueError("CSV row count differs from the manifest.")
    return tuple(samples)


def _validate_schema(manifest: dict) -> None:
    schema = manifest["schema"]
    expected = {
        "sample_version": SAMPLE_SCHEMA_VERSION,
        "feature_version": FEATURE_SCHEMA_VERSION,
        "columns": list(SAMPLE_COLUMNS),
        "features": list(FEATURE_COLUMNS),
        "target": TARGET_COLUMN,
        "road_types": list(ROAD_TYPES),
        "traffic_levels": list(TRAFFIC_LEVELS),
    }
    if any(schema[key] != value for key, value in expected.items()):
        raise ValueError("Dataset schema is incompatible with this version of the service.")


def load_dataset(directory: Path) -> LoadedDataset:
    """Validate bytes and reconstruct the original split before exposing any training data."""
    try:
        manifest_bytes = _read_bounded(directory / "manifest.json", 2 * 1024 * 1024)
        raw = json.loads(manifest_bytes)
        manifest = DatasetManifest.model_validate(raw)
        _validate_schema(raw)
        if set(manifest.files) != set(FILE_NAMES):
            raise ValueError("Manifest must declare exactly the five dataset CSVs.")
        identity = {
            "graph_sha256": manifest.graph.sha256,
            "configuration": raw["configuration"],
            "files": raw["files"],
        }
        if payload_checksum(identity) != manifest.dataset_id:
            raise ValueError("Dataset identity differs from its declared contents.")
        policy = raw["split_policy"]
        expected_policy = {
            "time_basis": "prediction_at",
            "group_by": "scenario_id",
            "intervals": "start_inclusive_end_exclusive",
            "label_availability": "at_or_before_partition_end",
            "ineligible_group": "exclude_entire_scenario",
        }
        plan = SplitPlan.model_validate(policy["boundaries"])
        if any(
            policy[key] != value for key, value in expected_policy.items()
        ) or plan != split_plan_for(manifest.configuration):
            raise ValueError("Dataset split policy is incompatible with its configuration.")
        rows = {}
        for name in FILE_NAMES:
            metadata = manifest.files[name]
            content = _read_bounded(directory / name, metadata.bytes)
            if (
                len(content) != metadata.bytes
                or hashlib.sha256(content).hexdigest() != metadata.sha256
            ):
                raise ValueError(f"Dataset integrity check failed: {name}")
            rows[name] = _read_samples(content, metadata.rows)
        split = split_samples(rows["samples.csv"], plan)
        for name, samples in split.subsets().items():
            if rows[f"{name}.csv"] != samples:
                raise ValueError(f"Partition does not match the temporal/group policy: {name}")
            summary = raw["partitions"][name]
            if summary["rows"] != len(samples) or summary["scenarios"] != len(
                {sample.scenario_id for sample in samples}
            ):
                raise ValueError(f"Partition summary differs from its records: {name}")
        if not split.train or not split.validation or not split.test:
            raise ValueError("Training, validation and test partitions must be nonempty.")
        if dict(split.excluded_reasons) != raw["excluded_scenarios"]:
            raise ValueError("Excluded scenarios differ from the split policy.")
        if any(
            sample.graph_version != manifest.graph.graph_version
            or sample.generator_version != GENERATOR_VERSION
            for sample in rows["samples.csv"]
        ):
            raise ValueError("Observation versions differ from the manifest.")
        return LoadedDataset(raw, hashlib.sha256(manifest_bytes).hexdigest(), split)
    except (OSError, UnicodeError, KeyError, TypeError, csv.Error, ValueError) as exc:
        raise ValueError(f"Unable to load dataset: {exc}") from exc
