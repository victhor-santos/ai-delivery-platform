from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from importlib.resources import as_file, files
from pathlib import Path
from types import MappingProxyType
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.ml.features import TrafficLevel
from app.routing.graph import MAX_SEGMENTS, Identifier, RoadGraph

MAX_TRAFFIC_BYTES = 128 * 1024


class TrafficConfigurationError(ValueError):
    """The configured synthetic traffic snapshot is missing or incompatible."""


class SyntheticTrafficScenario(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    scenario_version: Identifier
    graph_version: Identifier
    data_origin: Literal["synthetic"]
    levels: dict[Identifier, TrafficLevel] = Field(max_length=MAX_SEGMENTS)


@dataclass(frozen=True)
class SyntheticTrafficSnapshot:
    source: str
    observed_at: datetime
    available_at: datetime
    levels: Mapping[str, TrafficLevel]


def traffic_snapshot(
    scenario: SyntheticTrafficScenario, graph: RoadGraph, observed_at: datetime
) -> SyntheticTrafficSnapshot:
    if (
        scenario.graph_version != graph.graph_version
        or scenario.levels.keys() != graph.segments_by_id.keys()
    ):
        raise TrafficConfigurationError("Traffic must cover exactly the configured graph segments.")
    if observed_at.tzinfo is None or observed_at.utcoffset() is None:
        raise TrafficConfigurationError("Traffic availability must have an explicit timezone.")
    try:
        timestamp = observed_at.astimezone(UTC)
    except OverflowError as exc:
        raise TrafficConfigurationError(
            "Traffic timestamp exceeds the supported calendar."
        ) from exc
    return SyntheticTrafficSnapshot(
        source=scenario.scenario_version,
        observed_at=timestamp,
        available_at=timestamp,
        levels=MappingProxyType(dict(scenario.levels)),
    )


def load_traffic(path: Path, graph: RoadGraph, observed_at: datetime) -> SyntheticTrafficSnapshot:
    try:
        with path.open("rb") as source:
            content = source.read(MAX_TRAFFIC_BYTES + 1)
        if len(content) > MAX_TRAFFIC_BYTES:
            raise TrafficConfigurationError("Traffic file exceeds the 128 KiB limit.")
        scenario = SyntheticTrafficScenario.model_validate_json(content)
        return traffic_snapshot(scenario, graph, observed_at)
    except (OSError, ValueError) as exc:
        raise TrafficConfigurationError("Unable to load compatible synthetic traffic.") from exc


def load_demo_traffic(graph: RoadGraph, observed_at: datetime) -> SyntheticTrafficSnapshot:
    resource = files("app.routing").joinpath("data", "synthetic-traffic-v1.json")
    with as_file(resource) as path:
        return load_traffic(path, graph, observed_at)
