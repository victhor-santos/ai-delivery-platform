import re
from datetime import UTC, datetime
from typing import Annotated, Literal

from pydantic import AfterValidator, AwareDatetime, BaseModel, BeforeValidator, ConfigDict, Field

from app.ml.features import departure_context
from app.routing.coordinates import GeoPoint
from app.routing.graph import MAX_NODES, Identifier, PositiveNumber

RFC3339_PATTERN = r"^\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})$"


def _require_timestamp_text(value: object) -> str:
    if not isinstance(value, str) or len(value) > 64 or not re.fullmatch(RFC3339_PATTERN, value):
        raise ValueError("Departure must be an RFC 3339 timestamp with an explicit offset.")
    return value


def _normalize_departure(value: datetime) -> datetime:
    try:
        normalized = value.astimezone(UTC)
        departure_context(normalized)
        return normalized
    except OverflowError as exc:
        raise ValueError(
            "Departure exceeds the supported calendar in UTC or the graph timezone."
        ) from exc


class RouteModel(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class RouteRequest(RouteModel):
    origin: GeoPoint
    destination: GeoPoint
    departure_at: Annotated[
        AwareDatetime,
        BeforeValidator(_require_timestamp_text),
        AfterValidator(_normalize_departure),
        Field(json_schema_extra={"pattern": RFC3339_PATTERN, "maxLength": 64}),
    ]


class SegmentResponse(RouteModel):
    segment_id: Identifier
    distance_km: PositiveNumber
    predicted_travel_time_minutes: PositiveNumber


class RouteResponse(RouteModel):
    route: Annotated[tuple[GeoPoint, ...], Field(min_length=1, max_length=MAX_NODES)]
    segments: Annotated[tuple[SegmentResponse, ...], Field(max_length=MAX_NODES - 1)]
    distance_km: Annotated[float, Field(strict=True, ge=0, allow_inf_nan=False)]
    predicted_travel_time_minutes: Annotated[float, Field(strict=True, ge=0, allow_inf_nan=False)]
    predicted_at: AwareDatetime
    context_as_of: AwareDatetime
    model_version: str
    graph_version: Identifier
    data_origin: Literal["synthetic"] = "synthetic"
