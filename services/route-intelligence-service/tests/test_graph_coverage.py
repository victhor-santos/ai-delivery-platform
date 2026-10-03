import math

import pytest

from app.routing.coverage import EARTH_RADIUS_METERS, OutsideGraphCoverageError, match_node
from app.routing.demo import load_demo_graph
from app.routing.graph import RoadGraph
from app.routing.provenance import graph_checksum
from app.schemas.routes import GeoPoint


def test_exact_node_and_submeter_offset_map_to_original_coordinates():
    graph = load_demo_graph()
    node = graph.nodes_by_id["A"]
    assert match_node(graph, GeoPoint(lat=node.lat, lon=node.lon)) == node
    offset = math.degrees(0.5 / EARTH_RADIUS_METERS)
    assert match_node(graph, GeoPoint(lat=node.lat + offset, lon=node.lon)) == node
    with pytest.raises(OutsideGraphCoverageError):
        match_node(
            graph, GeoPoint(lat=node.lat + math.degrees(1.5 / EARTH_RADIUS_METERS), lon=node.lon)
        )


def test_equal_distance_uses_node_identifier_independent_of_json_order(graph_data):
    graph_data["nodes"][1].update(
        lat=graph_data["nodes"][0]["lat"], lon=graph_data["nodes"][0]["lon"]
    )
    point = GeoPoint(lat=graph_data["nodes"][0]["lat"], lon=graph_data["nodes"][0]["lon"])
    graph_data["nodes"].reverse()
    assert match_node(RoadGraph.model_validate(graph_data), point).node_id == "A"


def test_shared_checksum_preserves_existing_dataset_and_model_identity():
    assert graph_checksum(load_demo_graph()) == (
        "16d7e2c1185574eca863b5fc99a8df1f4368c1b79e3fa24cdaef9946eea2c649"
    )
