"""Load Delivery exports without treating simulated observations as training samples."""

import csv
import hashlib
import io
import math
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal, Self
from uuid import UUID

from pydantic import BeforeValidator, Field, model_validator

from app.ml.features import SegmentFeatures, departure_context
from app.routing.graph import Identifier, PositiveNumber
from training.schema import UtcTimestamp
from training.serialization import payload_checksum

OBSERVATION_COLUMNS = (
    "schema_version,traversal_id,delivery_id,route_plan_id,sequence,data_origin,"
    "prediction_data_origin,segment_id,from_node,to_node,graph_version,model_version,"
    "feature_schema_version,distance_km,road_type,reference_speed_kmh,traffic_level,hour,"
    "day_of_week,timezone,traffic_source,traffic_observed_at,traffic_available_at,"
    "features_available_at,prediction_at,planned_departure_at,context_as_of,"
    "predicted_travel_time_minutes,entered_at,entry_recorded_at,exited_at,recorded_at,"
    "label_available_at,actual_travel_time_minutes"
).split(",")
MAX_EXPORT_BYTES = 50 * 1024 * 1024
MAX_OBSERVATIONS = 100_000
MAX_EXPORT_FILES = 100


def _timestamp_input(value: object) -> datetime:
    if isinstance(value, datetime):
        return value
    if not isinstance(value, str) or len(value) > 64:
        raise ValueError("Export timestamps must be ISO text with an explicit timezone.")
    return datetime.fromisoformat(value)


ObservationTimestamp = Annotated[UtcTimestamp, BeforeValidator(_timestamp_input)]


class DeliveryObservation(SegmentFeatures):
    schema_version: Literal["delivery-segment-observation-v1"]
    traversal_id: UUID
    delivery_id: UUID
    route_plan_id: UUID
    sequence: Annotated[int, Field(strict=True, ge=0, le=198)]
    data_origin: Literal["simulated"]
    prediction_data_origin: Literal["synthetic"]
    segment_id: Identifier
    from_node: Identifier
    to_node: Identifier
    graph_version: Identifier
    model_version: Annotated[str, Field(strict=True, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")]
    feature_schema_version: Literal["segment-features-v1"]
    timezone: Literal["America/Sao_Paulo"]
    traffic_source: Identifier
    traffic_observed_at: ObservationTimestamp
    traffic_available_at: ObservationTimestamp
    features_available_at: ObservationTimestamp
    prediction_at: ObservationTimestamp
    planned_departure_at: ObservationTimestamp
    context_as_of: ObservationTimestamp
    predicted_travel_time_minutes: PositiveNumber
    entered_at: ObservationTimestamp
    entry_recorded_at: ObservationTimestamp
    exited_at: ObservationTimestamp
    recorded_at: ObservationTimestamp
    label_available_at: ObservationTimestamp
    actual_travel_time_minutes: PositiveNumber

    @model_validator(mode="after")
    def validate_history(self) -> Self:
        if (
            not (
                self.traffic_observed_at
                <= self.traffic_available_at
                <= self.features_available_at
                <= self.context_as_of
                <= self.prediction_at
                <= self.entered_at
                < self.exited_at
                <= self.recorded_at
                == self.label_available_at
            )
            or not self.entered_at <= self.entry_recorded_at <= self.label_available_at
        ):
            raise ValueError("Observation timestamps violate the export availability contract.")
        if self.from_node == self.to_node:
            raise ValueError("A traversal must connect different nodes.")
        if (self.hour, self.day_of_week) != departure_context(self.planned_departure_at):
            raise ValueError("Features must describe planned departure, not observed entry.")
        elapsed = (self.exited_at - self.entered_at).total_seconds() / 60
        if not math.isclose(self.actual_travel_time_minutes, elapsed, rel_tol=1e-9, abs_tol=1e-9):
            raise ValueError("Observed duration does not match the traversal timestamps.")
        return self


@dataclass(frozen=True)
class ExportSource:
    name: str
    sha256: str
    bytes: int
    rows: int


@dataclass(frozen=True)
class LoadedObservations:
    rows: tuple[DeliveryObservation, ...]
    sources: tuple[ExportSource, ...]
    duplicate_rows: int

    @property
    def observation_set_id(self) -> str:
        return payload_checksum([row.model_dump(mode="json") for row in self.rows])


def _parse_row(raw: dict[str, str]) -> DeliveryObservation:
    values: dict[str, object] = dict(raw)
    for column in (
        "distance_km",
        "reference_speed_kmh",
        "predicted_travel_time_minutes",
        "actual_travel_time_minutes",
    ):
        values[column] = float(raw[column])
    for column in ("sequence", "hour", "day_of_week"):
        values[column] = int(raw[column])
    return DeliveryObservation.model_validate(values)


def _validate_deliveries(rows: tuple[DeliveryObservation, ...]) -> None:
    deliveries = defaultdict(list)
    for row in rows:
        deliveries[row.delivery_id].append(row)
    for journey in deliveries.values():
        versions = {
            (
                row.route_plan_id,
                row.graph_version,
                row.model_version,
                row.prediction_at,
                row.planned_departure_at,
                row.context_as_of,
            )
            for row in journey
        }
        if len(versions) != 1:
            raise ValueError("A delivery contains conflicting prediction snapshots.")
        for previous, current in zip(journey, journey[1:], strict=False):
            if current.entered_at < previous.exited_at:
                raise ValueError("Traversal times overlap within a delivery.")
            if current.sequence == previous.sequence + 1 and previous.to_node != current.from_node:
                raise ValueError("Consecutive traversals do not describe a connected path.")


def load_observations(paths: list[Path]) -> LoadedObservations:
    """Validate every row before filtering; identical repeated exports count only once."""
    if not paths or len(paths) > MAX_EXPORT_FILES:
        raise ValueError(f"Provide between 1 and {MAX_EXPORT_FILES} CSV exports.")
    rows_by_id = {}
    sequence_ids = {}
    sources = []
    duplicates = 0
    total_rows = 0
    for path in paths:
        try:
            with path.open("rb") as source:
                content = source.read(MAX_EXPORT_BYTES + 1)
            if len(content) > MAX_EXPORT_BYTES:
                raise ValueError("Export exceeds the supported file size.")
            reader = csv.DictReader(
                io.StringIO(content.decode("utf-8-sig"), newline=""), strict=True
            )
            if reader.fieldnames != OBSERVATION_COLUMNS:
                raise ValueError("Columns must exactly match delivery-segment-observation-v1.")
            file_rows = 0
            for raw in reader:
                total_rows += 1
                file_rows += 1
                if total_rows > MAX_OBSERVATIONS:
                    raise ValueError("Exports exceed the supported total row count.")
                if None in raw or any(value is None or value == "" for value in raw.values()):
                    raise ValueError(f"Incomplete observation at CSV line {reader.line_num}.")
                row = _parse_row(raw)
                previous = rows_by_id.get(row.traversal_id)
                if previous is not None:
                    if previous != row:
                        raise ValueError("Conflicting observations share a traversal ID.")
                    duplicates += 1
                    continue
                key = (row.delivery_id, row.sequence)
                if key in sequence_ids:
                    raise ValueError("Different traversals share a delivery sequence.")
                sequence_ids[key] = row.traversal_id
                rows_by_id[row.traversal_id] = row
            sources.append(
                ExportSource(
                    path.name, hashlib.sha256(content).hexdigest(), len(content), file_rows
                )
            )
        except (OSError, UnicodeError, ValueError, csv.Error, OverflowError) as exc:
            raise ValueError(f"Unable to load observation export {path.name}: {exc}") from exc
    rows = tuple(sorted(rows_by_id.values(), key=lambda row: (str(row.delivery_id), row.sequence)))
    _validate_deliveries(rows)
    return LoadedObservations(
        rows, tuple(sorted(sources, key=lambda source: (source.name, source.sha256))), duplicates
    )
