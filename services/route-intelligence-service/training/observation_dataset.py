"""Prepare simulated Delivery observations with their own auditable dataset contract."""

import csv
import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.ml.features import FEATURE_COLUMNS, FEATURE_SCHEMA_VERSION, ROAD_TYPES, TRAFFIC_LEVELS
from training.observations import (
    MAX_EXPORT_BYTES,
    MAX_EXPORT_FILES,
    MAX_OBSERVATIONS,
    OBSERVATION_COLUMNS,
    DeliveryObservation,
    ExportSource,
    LoadedObservations,
    load_observations,
)
from training.schema import TARGET_COLUMN
from training.serialization import json_bytes, payload_checksum
from training.splits import DatasetSplit, SplitPlan

FILE_NAMES = ("samples.csv", "train.csv", "validation.csv", "test.csv", "excluded.csv")
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
SHA256 = Annotated[str, Field(strict=True, pattern=r"^[a-f0-9]{64}$")]


class ObservationFileMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rows: Annotated[int, Field(strict=True, ge=0, le=MAX_OBSERVATIONS)]
    bytes: Annotated[int, Field(strict=True, gt=0, le=MAX_EXPORT_BYTES)]
    sha256: SHA256


class ObservationSourceMetadata(ObservationFileMetadata):
    name: Annotated[str, Field(strict=True, min_length=1, max_length=255)]


class ObservationDatasetManifest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    manifest_version: Literal["delivery-observation-dataset-v1"]
    dataset_id: SHA256
    observation_set_id: SHA256
    data_origin: Literal["simulated"]
    prediction_data_origin: Literal["synthetic"]
    graph_version: str
    sources: Annotated[
        list[ObservationSourceMetadata], Field(min_length=1, max_length=MAX_EXPORT_FILES)
    ]
    duplicate_rows: Annotated[int, Field(strict=True, ge=0, le=MAX_OBSERVATIONS)]
    manifest_schema: dict = Field(alias="schema")
    split_policy: dict
    partitions: dict
    excluded_deliveries: dict[str, str]
    audit: dict
    files: dict[str, ObservationFileMetadata]


@dataclass(frozen=True)
class LoadedObservationDataset:
    manifest: dict
    manifest_sha256: str
    split: DatasetSplit[DeliveryObservation]

    @property
    def dataset_id(self) -> str:
        return self.manifest["dataset_id"]


def split_observations(
    data: LoadedObservations, plan: SplitPlan
) -> DatasetSplit[DeliveryObservation]:
    if not data.rows:
        raise ValueError("Cannot prepare an empty observation dataset.")
    if len({row.graph_version for row in data.rows}) != 1:
        raise ValueError("An observation dataset must use one graph version.")
    segments = {}
    deliveries = defaultdict(list)
    for row in data.rows:
        attributes = (
            row.from_node,
            row.to_node,
            row.distance_km,
            row.road_type,
            row.reference_speed_kmh,
        )
        if row.segment_id in segments and segments[row.segment_id] != attributes:
            raise ValueError("A segment has conflicting static attributes within the graph.")
        segments[row.segment_id] = attributes
        deliveries[str(row.delivery_id)].append(row)
    partitions = {name: [] for name in ("train", "validation", "test", "excluded")}
    reasons = {}
    for delivery_id, journey in sorted(deliveries.items()):
        journey.sort(key=lambda row: row.sequence)
        memberships = {plan.partition_at(row.prediction_at) for row in journey}
        if memberships == {"outside"}:
            reason = "outside_period"
        elif len(memberships) != 1:
            reason = "crosses_time_boundary"
        else:
            partition = memberships.pop()
            reason = (
                "label_unavailable_at_cutoff"
                if any(row.label_available_at > plan.cutoff_for(partition) for row in journey)
                else None
            )
        if reason is None:
            partitions[partition].extend(journey)
        else:
            reasons[delivery_id] = reason
            partitions["excluded"].extend(journey)
    return DatasetSplit(
        plan=plan,
        **{name: tuple(rows) for name, rows in partitions.items()},
        excluded_reasons=MappingProxyType(reasons),
    )


def _partition_summary(rows: tuple[DeliveryObservation, ...]) -> dict:
    timestamps = {
        "first_prediction_at": min((row.prediction_at for row in rows), default=None),
        "last_prediction_at": max((row.prediction_at for row in rows), default=None),
        "last_label_available_at": max((row.label_available_at for row in rows), default=None),
    }
    return {
        "rows": len(rows),
        "deliveries": len({row.delivery_id for row in rows}),
        "model_versions": dict(sorted(Counter(row.model_version for row in rows).items())),
    } | {key: value.isoformat() if value is not None else None for key, value in timestamps.items()}


def _dataset_identity(manifest: dict) -> dict:
    return {
        name: manifest[name]
        for name in (
            "manifest_version",
            "observation_set_id",
            "graph_version",
            "schema",
            "split_policy",
            "files",
        )
    }


def _manifest(
    data: LoadedObservations, split: DatasetSplit[DeliveryObservation], files: dict
) -> dict:
    memberships = defaultdict(set)
    for name in ("train", "validation", "test"):
        for row in getattr(split, name):
            memberships[row.delivery_id].add(name)
    empty = [name for name in ("train", "validation", "test") if not getattr(split, name)]
    manifest = {
        "manifest_version": "delivery-observation-dataset-v1",
        "observation_set_id": data.observation_set_id,
        "data_origin": "simulated",
        "prediction_data_origin": "synthetic",
        "graph_version": data.rows[0].graph_version,
        "sources": [asdict(source) for source in data.sources],
        "duplicate_rows": data.duplicate_rows,
        "schema": {
            "observation_version": "delivery-segment-observation-v1",
            "columns": OBSERVATION_COLUMNS,
            "feature_version": FEATURE_SCHEMA_VERSION,
            "features": FEATURE_COLUMNS,
            "target": TARGET_COLUMN,
            "road_types": ROAD_TYPES,
            "traffic_levels": TRAFFIC_LEVELS,
        },
        "split_policy": {
            "time_basis": "prediction_at",
            "group_by": "delivery_id",
            "intervals": "start_inclusive_end_exclusive",
            "label_availability": "at_or_before_partition_end",
            "ineligible_group": "exclude_entire_delivery",
            "boundaries": split.plan.model_dump(mode="json"),
        },
        "partitions": {name: _partition_summary(rows) for name, rows in split.subsets().items()},
        "excluded_deliveries": dict(split.excluded_reasons),
        "audit": {
            "delivery_ids_in_multiple_partitions": sum(
                len(names) > 1 for names in memberships.values()
            ),
            "all_partitions_nonempty": not empty,
            "empty_partitions": empty,
        },
        "files": files,
    }
    manifest["dataset_id"] = payload_checksum(_dataset_identity(manifest))
    return json.loads(json_bytes(manifest))


def _write_observations(path: Path, rows: tuple[DeliveryObservation, ...]) -> dict:
    with path.open("x", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=OBSERVATION_COLUMNS, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow(row.model_dump(mode="json"))
            if target.tell() > MAX_EXPORT_BYTES:
                raise ValueError(
                    "Prepared CSV exceeds the supported file size; use fewer deliveries."
                )
    with path.open("rb") as source:
        checksum = hashlib.file_digest(source, "sha256").hexdigest()
    return {"rows": len(rows), "bytes": path.stat().st_size, "sha256": checksum}


def export_observation_dataset(data: LoadedObservations, plan: SplitPlan, output: Path) -> dict:
    if output.exists():
        raise ValueError("Output already exists; choose a new observation dataset directory.")
    split = split_observations(data, plan)
    try:
        output.mkdir(parents=True, exist_ok=False)
        files = {
            f"{name}.csv": _write_observations(output / f"{name}.csv", rows)
            for name, rows in {"samples": data.rows, **split.subsets()}.items()
        }
        manifest = _manifest(data, split, files)
        content = json_bytes(manifest)
        if len(content) > MAX_MANIFEST_BYTES:
            raise ValueError("Observation dataset manifest exceeds the supported file size.")
        with (output / "manifest.json").open("xb") as target:
            target.write(content)
        return manifest
    except OSError as exc:
        raise ValueError(
            "Unable to prepare observations; an incomplete directory has no valid manifest."
        ) from exc


def load_observation_dataset(directory: Path) -> LoadedObservationDataset:
    """Check file integrity and reconstruct every partition before exposing its rows."""
    try:
        with (directory / "manifest.json").open("rb") as source:
            content = source.read(MAX_MANIFEST_BYTES + 1)
        if len(content) > MAX_MANIFEST_BYTES:
            raise ValueError("Observation dataset manifest exceeds the supported file size.")
        raw = json.loads(content)
        metadata = ObservationDatasetManifest.model_validate(raw)
        if set(metadata.files) != set(FILE_NAMES):
            raise ValueError("Manifest must declare exactly the five observation CSVs.")
        if payload_checksum(_dataset_identity(raw)) != metadata.dataset_id:
            raise ValueError("Observation dataset identity differs from its declared contents.")
        loaded = {}
        for name in FILE_NAMES:
            records = load_observations([directory / name])
            expected = metadata.files[name]
            actual = records.sources[0]
            if (actual.rows, actual.bytes, actual.sha256) != (
                expected.rows,
                expected.bytes,
                expected.sha256,
            ):
                raise ValueError(f"Observation dataset integrity check failed: {name}")
            if records.duplicate_rows:
                raise ValueError("Prepared CSVs must contain unique observations.")
            loaded[name] = records.rows
        rows = loaded["samples.csv"]
        source_rows = sum(source.rows for source in metadata.sources)
        if source_rows != len(rows) + metadata.duplicate_rows or source_rows > MAX_OBSERVATIONS:
            raise ValueError(
                "Original source counts differ from the unique observations and duplicates."
            )
        data = LoadedObservations(
            rows,
            tuple(ExportSource(**source.model_dump()) for source in metadata.sources),
            metadata.duplicate_rows,
        )
        plan = SplitPlan.model_validate(metadata.split_policy["boundaries"])
        split = split_observations(data, plan)
        for name, expected in split.subsets().items():
            if loaded[f"{name}.csv"] != expected:
                raise ValueError(f"Partition differs from the temporal delivery policy: {name}")
        expected_manifest = _manifest(data, split, raw["files"])
        if json_bytes(raw) != json_bytes(expected_manifest):
            raise ValueError(
                "Observation dataset schema, policy, provenance or summary is inconsistent."
            )
        return LoadedObservationDataset(raw, hashlib.sha256(content).hexdigest(), split)
    except (OSError, UnicodeError, KeyError, TypeError, ValueError, OverflowError) as exc:
        raise ValueError(f"Unable to load observation dataset: {exc}") from exc
