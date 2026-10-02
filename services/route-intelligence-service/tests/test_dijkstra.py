from dataclasses import FrozenInstanceError
from itertools import permutations
from random import Random

import pytest

from app.routing.dijkstra import (
    InvalidRouteCostsError,
    NodeNotFoundError,
    RouteNotFoundError,
    find_fastest_route,
    reference_travel_times,
)
from app.routing.graph import RoadGraph


def add_segment(data, segment_id, origin, destination, distance=1, speed=30):
    data["segments"].append(
        {
            "segment_id": segment_id,
            "from_node": origin,
            "to_node": destination,
            "distance_km": distance,
            "road_type": "primary",
            "reference_speed_kmh": speed,
        }
    )


def test_reference_costs_use_distance_and_speed_in_minutes(graph_data):
    costs = reference_travel_times(RoadGraph.model_validate(graph_data))

    assert costs == pytest.approx({"A-B": 3.6, "B-C": 4.0})


def test_route_preserves_order_metadata_and_unrounded_sums(graph_data):
    graph = RoadGraph.model_validate(graph_data)
    costs = {"A-B": 1 / 3, "B-C": 2 / 3}

    route = find_fastest_route(graph, "A", "C", costs)

    assert route.node_ids == ("A", "B", "C")
    assert [segment.segment_id for segment in route.segments] == ["A-B", "B-C"]
    assert route.graph_version == graph.graph_version
    assert route.distance_km == pytest.approx(2.9)
    assert route.travel_time_minutes == pytest.approx(1.0)
    assert route.segments[0].travel_time_minutes == 1 / 3
    assert route.travel_time_minutes == sum(s.travel_time_minutes for s in route.segments)
    assert route.distance_km == sum(s.distance_km for s in route.segments)


def test_origin_equal_to_destination_returns_one_node_and_zero_totals(graph_data):
    graph = RoadGraph.model_validate(graph_data)

    route = find_fastest_route(graph, "B", "B", reference_travel_times(graph))

    assert route.node_ids == ("B",)
    assert route.segments == ()
    assert route.distance_km == 0
    assert route.travel_time_minutes == 0


def test_direction_is_not_implicitly_reversed(graph_data):
    graph = RoadGraph.model_validate(graph_data)

    with pytest.raises(RouteNotFoundError):
        find_fastest_route(graph, "C", "A", reference_travel_times(graph))


def test_disconnected_destination_has_no_route(graph_data):
    graph_data["nodes"].append({"node_id": "D", "lat": 0, "lon": 0})
    graph = RoadGraph.model_validate(graph_data)

    with pytest.raises(RouteNotFoundError):
        find_fastest_route(graph, "A", "D", reference_travel_times(graph))


@pytest.mark.parametrize(
    "origin,destination", [("missing", "C"), ("A", "missing"), ("missing", "missing")]
)
def test_unknown_endpoint_is_distinct_from_no_route(graph_data, origin, destination):
    graph = RoadGraph.model_validate(graph_data)

    with pytest.raises(NodeNotFoundError):
        find_fastest_route(graph, origin, destination, reference_travel_times(graph))


def test_single_isolated_node_can_route_to_itself(graph_data):
    graph_data["nodes"] = graph_data["nodes"][:1]
    graph_data["segments"] = []
    graph = RoadGraph.model_validate(graph_data)

    assert find_fastest_route(graph, "A", "A", {}).node_ids == ("A",)


def test_longer_route_can_win_by_time(graph_data):
    add_segment(graph_data, "A-C", "A", "C", distance=1, speed=5)
    graph = RoadGraph.model_validate(graph_data)

    route = find_fastest_route(graph, "A", "C", reference_travel_times(graph))

    assert route.node_ids == ("A", "B", "C")
    assert route.distance_km > graph.segments_by_id["A-C"].distance_km
    assert route.travel_time_minutes == pytest.approx(7.6)
    assert route.travel_time_minutes < 12


def test_improved_path_replaces_a_previously_discovered_path(graph_data):
    add_segment(graph_data, "A-C", "A", "C")
    graph = RoadGraph.model_validate(graph_data)

    route = find_fastest_route(graph, "A", "C", {"A-B": 1, "B-C": 1, "A-C": 10})

    assert route.node_ids == ("A", "B", "C")
    assert route.travel_time_minutes == 2


def test_cycles_self_loops_and_stale_heap_entries_do_not_break_search(graph_data):
    graph_data["nodes"].append({"node_id": "D", "lat": 0, "lon": 0})
    add_segment(graph_data, "A-C", "A", "C")
    add_segment(graph_data, "C-D", "C", "D")
    add_segment(graph_data, "B-A", "B", "A")
    add_segment(graph_data, "C-C", "C", "C")
    graph = RoadGraph.model_validate(graph_data)
    costs = {"A-B": 1, "B-C": 1, "A-C": 10, "C-D": 20, "B-A": 1, "C-C": 1}

    route = find_fastest_route(graph, "A", "D", costs)

    assert route.node_ids == ("A", "B", "C", "D")
    assert route.travel_time_minutes == 22


def test_equal_cost_ties_are_stable_for_all_segment_loading_orders(graph_data):
    graph_data["nodes"].append({"node_id": "D", "lat": 0, "lon": 0})
    graph_data["segments"] = []
    add_segment(graph_data, "A-C", "A", "C")
    add_segment(graph_data, "C-D", "C", "D")
    add_segment(graph_data, "A-B", "A", "B")
    add_segment(graph_data, "B-D", "B", "D")
    costs = {segment["segment_id"]: 1 for segment in graph_data["segments"]}

    for segments in permutations(graph_data["segments"]):
        for nodes in (graph_data["nodes"], list(reversed(graph_data["nodes"]))):
            graph = RoadGraph.model_validate(dict(graph_data, nodes=nodes, segments=segments))

            assert find_fastest_route(graph, "A", "D", costs).node_ids == ("A", "B", "D")


def test_equal_cost_keeps_first_discovered_predecessor(graph_data):
    add_segment(graph_data, "A-C", "A", "C")
    graph = RoadGraph.model_validate(graph_data)

    assert find_fastest_route(graph, "A", "C", {"A-B": 1, "B-C": 1, "A-C": 2}).node_ids == (
        "A",
        "C",
    )


@pytest.mark.parametrize(
    "value", [0, -1, float("nan"), float("inf"), float("-inf"), "2", True, None, 10**400]
)
def test_invalid_costs_are_rejected(graph_data, value):
    graph = RoadGraph.model_validate(graph_data)

    with pytest.raises(InvalidRouteCostsError):
        find_fastest_route(graph, "A", "C", {"A-B": value, "B-C": 1})


@pytest.mark.parametrize("costs", [{"A-B": 1}, {"A-B": 1, "B-C": 1, "extra": 1}])
def test_costs_must_match_exactly_the_graph_segments(graph_data, costs):
    graph = RoadGraph.model_validate(graph_data)

    with pytest.raises(InvalidRouteCostsError):
        find_fastest_route(graph, "A", "C", costs)


def test_invalid_cost_on_unreachable_segment_is_not_ignored(graph_data):
    add_segment(graph_data, "C-B", "C", "B")
    graph = RoadGraph.model_validate(graph_data)

    with pytest.raises(InvalidRouteCostsError):
        find_fastest_route(graph, "A", "B", {"A-B": 1, "B-C": 1, "C-B": float("nan")})


def test_travel_time_overflow_is_rejected(graph_data):
    graph = RoadGraph.model_validate(graph_data)

    with pytest.raises(InvalidRouteCostsError, match="Accumulated travel time"):
        find_fastest_route(graph, "A", "C", {"A-B": 1e308, "B-C": 1e308})


def test_distance_overflow_is_rejected(graph_data):
    for segment in graph_data["segments"]:
        segment["distance_km"] = 1e308
    graph = RoadGraph.model_validate(graph_data)

    with pytest.raises(InvalidRouteCostsError, match="Accumulated distance"):
        find_fastest_route(graph, "A", "C", {"A-B": 1, "B-C": 1})


def test_reference_cost_overflow_is_rejected(graph_data):
    graph_data["segments"][0].update(distance_km=1e308, reference_speed_kmh=1e-300)

    with pytest.raises(InvalidRouteCostsError):
        reference_travel_times(RoadGraph.model_validate(graph_data))


def test_each_calculation_uses_its_own_costs_and_preserves_graph(graph_data):
    add_segment(graph_data, "A-C", "A", "C")
    graph = RoadGraph.model_validate(graph_data)
    before = graph.model_dump()
    first_costs = {"A-B": 1, "B-C": 1, "A-C": 10}
    second_costs = {"A-B": 10, "B-C": 10, "A-C": 1}

    first = find_fastest_route(graph, "A", "C", first_costs)
    second = find_fastest_route(graph, "A", "C", second_costs)

    assert first.node_ids == ("A", "B", "C")
    assert second.node_ids == ("A", "C")
    assert graph.model_dump() == before
    assert first_costs == {"A-B": 1, "B-C": 1, "A-C": 10}
    second_costs["A-C"] = 99
    assert second.travel_time_minutes == 1
    with pytest.raises(FrozenInstanceError):
        second.travel_time_minutes = 99


def exhaustive_path_costs(graph, costs, origin, destination, visited):
    if origin == destination:
        return [0]
    return [
        costs[segment.segment_id] + remainder
        for segment in graph.segments
        if segment.from_node == origin and segment.to_node not in visited
        for remainder in exhaustive_path_costs(
            graph, costs, segment.to_node, destination, visited | {segment.to_node}
        )
    ]


def test_costs_match_exhaustive_simple_paths_on_small_seeded_graphs(graph_data):
    random = Random(42)
    for _ in range(20):
        graph_data["nodes"] = [{"node_id": str(i), "lat": 0, "lon": 0} for i in range(6)]
        graph_data["segments"] = []
        costs = {}
        for origin in range(6):
            for destination in range(6):
                if origin != destination and random.random() < 0.3:
                    segment_id = f"{origin}-{destination}"
                    add_segment(graph_data, segment_id, str(origin), str(destination))
                    costs[segment_id] = random.randint(1, 10)
        graph = RoadGraph.model_validate(graph_data)

        for origin in graph.nodes_by_id:
            for destination in graph.nodes_by_id:
                expected = exhaustive_path_costs(graph, costs, origin, destination, {origin})
                if expected:
                    route = find_fastest_route(graph, origin, destination, costs)
                    assert route.travel_time_minutes == min(expected)
                    assert route.node_ids[0] == origin
                    assert route.node_ids[-1] == destination
                else:
                    with pytest.raises(RouteNotFoundError):
                        find_fastest_route(graph, origin, destination, costs)
