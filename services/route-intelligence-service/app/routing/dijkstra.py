import heapq
import math
from collections.abc import Mapping
from dataclasses import dataclass

from app.routing.graph import RoadGraph, RoadSegment


class NodeNotFoundError(ValueError):
    """An endpoint identifier is not in the graph."""


class RouteNotFoundError(ValueError):
    """No directed path connects the endpoints."""


class InvalidRouteCostsError(ValueError):
    """Segment costs or route totals are missing, unsupported or non-finite."""


@dataclass(frozen=True)
class RouteSegment:
    segment_id: str
    distance_km: float
    travel_time_minutes: float


@dataclass(frozen=True)
class Route:
    graph_version: str
    node_ids: tuple[str, ...]
    segments: tuple[RouteSegment, ...]
    distance_km: float
    travel_time_minutes: float


def _validated_costs(graph: RoadGraph, travel_times: Mapping[str, float]) -> dict[str, float]:
    snapshot = dict(travel_times)
    if snapshot.keys() != graph.segments_by_id.keys():
        raise InvalidRouteCostsError("Provide exactly one travel time for every graph segment.")
    costs: dict[str, float] = {}
    for segment_id, value in snapshot.items():
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise InvalidRouteCostsError("Travel times must be positive finite numbers.")
        try:
            cost = float(value)
        except OverflowError as exc:
            raise InvalidRouteCostsError("Travel times must be positive finite numbers.") from exc
        if not math.isfinite(cost) or cost <= 0:
            raise InvalidRouteCostsError("Travel times must be positive finite numbers.")
        costs[segment_id] = cost
    return costs


def reference_travel_times(graph: RoadGraph) -> dict[str, float]:
    """Physical reference in minutes; not a prediction or a traffic observation."""
    return _validated_costs(
        graph,
        {
            segment.segment_id: segment.distance_km / segment.reference_speed_kmh * 60
            for segment in graph.segments
        },
    )


def find_fastest_route(
    graph: RoadGraph,
    origin_id: str,
    destination_id: str,
    travel_times: Mapping[str, float],
) -> Route:
    for node_id in (origin_id, destination_id):
        if node_id not in graph.nodes_by_id:
            raise NodeNotFoundError(f"Unknown graph node: {node_id}")
    costs = _validated_costs(graph, travel_times)
    adjacency = graph.outgoing
    best_times = {origin_id: 0.0}
    predecessors: dict[str, RoadSegment] = {}
    pending = [(0.0, origin_id)]

    while pending:
        elapsed, node_id = heapq.heappop(pending)
        if elapsed != best_times[node_id]:
            continue
        if node_id == destination_id:
            return _build_route(graph, origin_id, destination_id, predecessors, costs, elapsed)
        for segment in adjacency[node_id]:
            candidate = elapsed + costs[segment.segment_id]
            if not math.isfinite(candidate):
                raise InvalidRouteCostsError("Accumulated travel time must be finite.")
            if candidate < best_times.get(segment.to_node, math.inf):
                best_times[segment.to_node] = candidate
                predecessors[segment.to_node] = segment
                heapq.heappush(pending, (candidate, segment.to_node))

    raise RouteNotFoundError(f"No directed route from {origin_id} to {destination_id}.")


def _build_route(
    graph: RoadGraph,
    origin_id: str,
    destination_id: str,
    predecessors: Mapping[str, RoadSegment],
    costs: Mapping[str, float],
    elapsed: float,
) -> Route:
    reversed_segments: list[RoadSegment] = []
    node_id = destination_id
    while node_id != origin_id:
        segment = predecessors[node_id]
        reversed_segments.append(segment)
        node_id = segment.from_node
    ordered = tuple(reversed(reversed_segments))
    distance_km = sum(segment.distance_km for segment in ordered)
    if not math.isfinite(distance_km):
        raise InvalidRouteCostsError("Accumulated distance must be finite.")
    return Route(
        graph_version=graph.graph_version,
        node_ids=(origin_id, *(segment.to_node for segment in ordered)),
        segments=tuple(
            RouteSegment(segment.segment_id, segment.distance_km, costs[segment.segment_id])
            for segment in ordered
        ),
        distance_km=distance_km,
        travel_time_minutes=elapsed,
    )
