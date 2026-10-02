import json
from copy import deepcopy

import pytest
from pydantic import ValidationError

from app.routing.graph import (
    MAX_GRAPH_BYTES,
    MAX_NODES,
    MAX_SEGMENTS,
    GraphLoadError,
    RoadGraph,
    load_graph,
)


def test_graph_loads_directed_segments_and_preserves_metadata(graph_data, tmp_path):
    path = tmp_path / "graph.json"
    path.write_text(json.dumps(graph_data), encoding="utf-8")

    graph = load_graph(path)

    assert graph.graph_version == "test-v1"
    assert graph.timezone == "America/Sao_Paulo"
    assert graph.vehicle_profile == "motorcycle"
    assert graph.data_origin == "synthetic"
    assert graph.nodes_by_id["A"].lat == -23.5505
    assert [segment.to_node for segment in graph.outgoing["A"]] == ["B"]
    assert graph.outgoing["C"] == ()
    assert graph.segments_by_id["A-B"].distance_km == 0.9


@pytest.mark.parametrize("field", ["lat", "lon"])
@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf"), "0", True])
def test_coordinates_must_be_finite_numbers(graph_data, field, value):
    graph_data["nodes"][0][field] = value

    with pytest.raises(ValidationError):
        RoadGraph.model_validate(graph_data)


@pytest.mark.parametrize(
    "field,value", [("lat", -90.01), ("lat", 90.01), ("lon", -180.01), ("lon", 180.01)]
)
def test_coordinates_must_be_in_geographic_range(graph_data, field, value):
    graph_data["nodes"][0][field] = value

    with pytest.raises(ValidationError):
        RoadGraph.model_validate(graph_data)


@pytest.mark.parametrize("lat,lon", [(-90, -180), (90, 180), (0, 0)])
def test_coordinate_boundaries_and_zero_are_valid(graph_data, lat, lon):
    graph_data["nodes"][0].update(lat=lat, lon=lon)

    assert RoadGraph.model_validate(graph_data).nodes_by_id["A"].lat == lat


@pytest.mark.parametrize("field", ["distance_km", "reference_speed_kmh"])
@pytest.mark.parametrize("value", [0, -1, float("nan"), float("inf"), float("-inf"), "10", True])
def test_segment_metrics_must_be_positive_finite_numbers(graph_data, field, value):
    graph_data["segments"][0][field] = value

    with pytest.raises(ValidationError):
        RoadGraph.model_validate(graph_data)


@pytest.mark.parametrize(
    "field,value",
    [
        ("timezone", "UTC"),
        ("vehicle_profile", "car"),
        ("data_origin", "real"),
        ("graph_version", ""),
        ("graph_version", " synthetic-v1"),
    ],
)
def test_unsupported_metadata_is_rejected(graph_data, field, value):
    graph_data[field] = value

    with pytest.raises(ValidationError):
        RoadGraph.model_validate(graph_data)


@pytest.mark.parametrize("value", ["", " ", "A B", "A" * 65, 12])
def test_invalid_node_identifier_is_rejected(graph_data, value):
    graph_data["nodes"][0]["node_id"] = value

    with pytest.raises(ValidationError):
        RoadGraph.model_validate(graph_data)


def test_duplicate_node_is_rejected(graph_data):
    graph_data["nodes"].append(deepcopy(graph_data["nodes"][0]))

    with pytest.raises(ValidationError, match="Node identifiers must be unique"):
        RoadGraph.model_validate(graph_data)


def test_duplicate_segment_identifier_is_rejected(graph_data):
    graph_data["segments"][1]["segment_id"] = "A-B"

    with pytest.raises(ValidationError, match="Segment identifiers must be unique"):
        RoadGraph.model_validate(graph_data)


@pytest.mark.parametrize("field", ["from_node", "to_node"])
def test_unknown_segment_endpoint_is_rejected(graph_data, field):
    graph_data["segments"][0][field] = "unknown"

    with pytest.raises(ValidationError, match="existing node"):
        RoadGraph.model_validate(graph_data)


def test_parallel_segments_are_rejected(graph_data):
    parallel = dict(graph_data["segments"][0], segment_id="parallel")
    graph_data["segments"].append(parallel)

    with pytest.raises(ValidationError, match="Parallel segments"):
        RoadGraph.model_validate(graph_data)


def test_reverse_direction_is_an_independent_segment(graph_data):
    reverse = dict(graph_data["segments"][0], segment_id="B-A", from_node="B", to_node="A")
    graph_data["segments"].append(reverse)

    graph = RoadGraph.model_validate(graph_data)

    assert {segment.segment_id for segment in graph.outgoing["B"]} == {"B-A", "B-C"}


def test_unknown_road_type_is_rejected(graph_data):
    graph_data["segments"][0]["road_type"] = "footpath"

    with pytest.raises(ValidationError):
        RoadGraph.model_validate(graph_data)


@pytest.mark.parametrize("location", ["graph", "node", "segment"])
def test_extra_fields_are_rejected(graph_data, location):
    target = {
        "graph": graph_data,
        "node": graph_data["nodes"][0],
        "segment": graph_data["segments"][0],
    }[location]
    target["unexpected"] = "value"

    with pytest.raises(ValidationError, match="Extra inputs"):
        RoadGraph.model_validate(graph_data)


def test_graph_and_indexes_are_immutable(graph_data):
    graph = RoadGraph.model_validate(graph_data)

    with pytest.raises(ValidationError, match="frozen"):
        graph.nodes[0].lat = 0
    with pytest.raises(ValidationError, match="frozen"):
        graph.segments[0].distance_km = 100
    with pytest.raises(ValidationError, match="frozen"):
        graph.graph_version = "other-v1"
    with pytest.raises(TypeError):
        graph.nodes_by_id["A"] = graph.nodes[1]
    with pytest.raises(TypeError):
        graph.outgoing["A"] = ()
    with pytest.raises(ValidationError, match="frozen"):
        graph.outgoing = {}


def test_empty_graph_is_rejected_but_isolated_nodes_are_allowed(graph_data):
    graph_data["segments"] = []
    graph = RoadGraph.model_validate(graph_data)
    assert graph.outgoing["A"] == ()
    graph_data["nodes"] = []

    with pytest.raises(ValidationError):
        RoadGraph.model_validate(graph_data)


def test_node_limit_is_enforced(graph_data):
    graph_data["nodes"] = [{"node_id": f"N{i}", "lat": 0, "lon": 0} for i in range(MAX_NODES + 1)]
    graph_data["segments"] = []

    with pytest.raises(ValidationError):
        RoadGraph.model_validate(graph_data)


def test_segment_limit_is_enforced(graph_data):
    graph_data["segments"] *= MAX_SEGMENTS // len(graph_data["segments"]) + 1

    with pytest.raises(ValidationError):
        RoadGraph.model_validate(graph_data)


@pytest.mark.parametrize("content", ["not json", "{}", '{"nodes": ['])
def test_invalid_file_is_reported_as_graph_load_error(tmp_path, content):
    path = tmp_path / "graph.json"
    path.write_text(content, encoding="utf-8")

    with pytest.raises(GraphLoadError):
        load_graph(path)


def test_missing_file_is_reported_as_graph_load_error(tmp_path):
    with pytest.raises(GraphLoadError):
        load_graph(tmp_path / "missing.json")


def test_file_size_limit_is_enforced(tmp_path):
    path = tmp_path / "oversized.json"
    path.write_bytes(b" " * (MAX_GRAPH_BYTES + 1))

    with pytest.raises(GraphLoadError, match="1 MiB"):
        load_graph(path)
