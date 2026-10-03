import math

from app.routing.coordinates import GeoPoint
from app.routing.graph import GraphNode, RoadGraph

EARTH_RADIUS_METERS = 6_371_000
NODE_TOLERANCE_METERS = 1.0


class OutsideGraphCoverageError(ValueError):
    """No graph node is within the supported coordinate tolerance."""


def distance_meters(point: GeoPoint, node: GraphNode) -> float:
    latitude = math.radians(point.lat)
    node_latitude = math.radians(node.lat)
    haversine = (
        math.sin((node_latitude - latitude) / 2) ** 2
        + math.cos(latitude)
        * math.cos(node_latitude)
        * math.sin(math.radians(node.lon - point.lon) / 2) ** 2
    )
    return 2 * EARTH_RADIUS_METERS * math.asin(math.sqrt(min(1.0, max(0.0, haversine))))


def match_node(graph: RoadGraph, point: GeoPoint) -> GraphNode:
    distance, _, node = min(
        (distance_meters(point, node), node.node_id, node) for node in graph.nodes
    )
    if distance > NODE_TOLERANCE_METERS:
        raise OutsideGraphCoverageError("Point is outside the synthetic graph coverage.")
    return node
