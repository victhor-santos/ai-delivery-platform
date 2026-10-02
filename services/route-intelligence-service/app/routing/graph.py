from pathlib import Path
from types import MappingProxyType
from typing import Annotated, Literal, Self

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    ValidationError,
    model_validator,
)

MAX_NODES = 200
MAX_SEGMENTS = 1000
MAX_GRAPH_BYTES = 1024 * 1024

Identifier = Annotated[
    str, StringConstraints(strict=True, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
]
PositiveNumber = Annotated[float, Field(strict=True, gt=0, allow_inf_nan=False)]


class GraphModel(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class GraphNode(GraphModel):
    node_id: Identifier
    lat: Annotated[float, Field(strict=True, ge=-90, le=90, allow_inf_nan=False)]
    lon: Annotated[float, Field(strict=True, ge=-180, le=180, allow_inf_nan=False)]


class RoadSegment(GraphModel):
    segment_id: Identifier
    from_node: Identifier
    to_node: Identifier
    distance_km: PositiveNumber
    road_type: Literal["residential", "primary", "highway"]
    reference_speed_kmh: PositiveNumber


class RoadGraph(GraphModel):
    graph_version: Identifier
    timezone: Literal["America/Sao_Paulo"]
    vehicle_profile: Literal["motorcycle"]
    data_origin: Literal["synthetic"]
    nodes: Annotated[tuple[GraphNode, ...], Field(min_length=1, max_length=MAX_NODES)]
    segments: Annotated[tuple[RoadSegment, ...], Field(max_length=MAX_SEGMENTS)]

    @model_validator(mode="after")
    def validate_topology(self) -> Self:
        node_ids = {node.node_id for node in self.nodes}
        if len(node_ids) != len(self.nodes):
            raise ValueError("Node identifiers must be unique.")
        segment_ids: set[str] = set()
        connections: set[tuple[str, str]] = set()
        for segment in self.segments:
            if segment.segment_id in segment_ids:
                raise ValueError("Segment identifiers must be unique.")
            if segment.from_node not in node_ids or segment.to_node not in node_ids:
                raise ValueError("Every segment endpoint must reference an existing node.")
            connection = (segment.from_node, segment.to_node)
            if connection in connections:
                raise ValueError("Parallel segments in the same direction are not supported.")
            segment_ids.add(segment.segment_id)
            connections.add(connection)
        return self

    @property
    def nodes_by_id(self) -> MappingProxyType[str, GraphNode]:
        return MappingProxyType({node.node_id: node for node in self.nodes})

    @property
    def segments_by_id(self) -> MappingProxyType[str, RoadSegment]:
        return MappingProxyType({segment.segment_id: segment for segment in self.segments})

    @property
    def outgoing(self) -> MappingProxyType[str, tuple[RoadSegment, ...]]:
        adjacency: dict[str, list[RoadSegment]] = {node.node_id: [] for node in self.nodes}
        for segment in self.segments:
            adjacency[segment.from_node].append(segment)
        return MappingProxyType(
            {
                node_id: tuple(sorted(segments, key=lambda segment: segment.segment_id))
                for node_id, segments in adjacency.items()
            }
        )


class GraphLoadError(ValueError):
    """A graph file could not be read or did not satisfy the supported schema."""


def load_graph(path: Path) -> RoadGraph:
    try:
        with path.open("rb") as source:
            content = source.read(MAX_GRAPH_BYTES + 1)
        if len(content) > MAX_GRAPH_BYTES:
            raise GraphLoadError("Graph file exceeds the 1 MiB limit.")
        return RoadGraph.model_validate_json(content)
    except (OSError, ValidationError) as exc:
        raise GraphLoadError("Unable to load a valid road graph.") from exc
