from collections.abc import Mapping
from datetime import datetime
from functools import lru_cache
from importlib.resources import files
from typing import Annotated, Literal, get_args
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field

from app.routing.graph import PositiveNumber, RoadSegment, RoadType

FEATURE_SCHEMA_VERSION = "segment-features-v1"
FEATURE_COLUMNS = (
    "distance_km",
    "road_type",
    "reference_speed_kmh",
    "traffic_level",
    "hour",
    "day_of_week",
)
TrafficLevel = Literal["low", "medium", "high"]
ROAD_TYPES = get_args(RoadType)
TRAFFIC_LEVELS = get_args(TrafficLevel)


class SegmentFeatures(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    distance_km: PositiveNumber
    road_type: RoadType
    reference_speed_kmh: PositiveNumber
    traffic_level: TrafficLevel
    hour: Annotated[int, Field(strict=True, ge=0, le=23)]
    day_of_week: Annotated[int, Field(strict=True, ge=0, le=6)]


@lru_cache(maxsize=1)
def graph_timezone() -> ZoneInfo:
    resource = files("tzdata.zoneinfo").joinpath("America", "Sao_Paulo")
    with resource.open("rb") as source:
        return ZoneInfo.from_file(source, key="America/Sao_Paulo")


def departure_context(departure_at: datetime) -> tuple[int, int]:
    if departure_at.tzinfo is None or departure_at.utcoffset() is None:
        raise ValueError("Planned departure must have an explicit timezone.")
    local = departure_at.astimezone(graph_timezone())
    return local.hour, local.weekday()


def features_for_segment(
    segment: RoadSegment, traffic_level: TrafficLevel, departure_at: datetime
) -> SegmentFeatures:
    hour, day_of_week = departure_context(departure_at)
    return SegmentFeatures(
        distance_km=segment.distance_km,
        road_type=segment.road_type,
        reference_speed_kmh=segment.reference_speed_kmh,
        traffic_level=traffic_level,
        hour=hour,
        day_of_week=day_of_week,
    )


def extract_features(record: Mapping[str, object]) -> SegmentFeatures:
    return SegmentFeatures.model_validate(
        {column: record.get(column) for column in FEATURE_COLUMNS}
    )
