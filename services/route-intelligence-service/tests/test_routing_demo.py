import json
import math

import pytest

from app.routing.__main__ import main
from app.routing.demo import load_demo_graph
from app.routing.dijkstra import RouteNotFoundError, find_fastest_route, reference_travel_times


def test_demo_chooses_longer_faster_route_from_fictional_restaurant():
    graph = load_demo_graph()
    costs = reference_travel_times(graph)

    route = find_fastest_route(graph, "A", "C", costs)

    assert graph.graph_version == "synthetic-city-v1"
    assert len(graph.nodes) == 7
    assert len(graph.segments) == 10
    assert route.node_ids == ("A", "D", "E", "C")
    assert route.distance_km == pytest.approx(5.0)
    assert route.travel_time_minutes == pytest.approx(187 / 30)
    assert route.distance_km > 0.9 + 2.0
    assert route.travel_time_minutes < costs["A-B"] + costs["B-C"]


def test_return_route_requires_explicit_reverse_segments():
    graph = load_demo_graph()

    route = find_fastest_route(graph, "C", "A", reference_travel_times(graph))

    assert route.node_ids == ("C", "B", "A")
    assert route.travel_time_minutes == pytest.approx(11.6)


def test_demo_disconnected_node_has_no_route():
    graph = load_demo_graph()

    with pytest.raises(RouteNotFoundError):
        find_fastest_route(graph, "A", "G", reference_travel_times(graph))


def test_demo_distances_are_not_shorter_than_straight_line_between_endpoints():
    graph = load_demo_graph()
    nodes = graph.nodes_by_id
    for segment in graph.segments:
        origin, destination = nodes[segment.from_node], nodes[segment.to_node]
        latitude_delta = math.radians(destination.lat - origin.lat)
        longitude_delta = math.radians(destination.lon - origin.lon)
        haversine = math.sin(latitude_delta / 2) ** 2 + (
            math.cos(math.radians(origin.lat))
            * math.cos(math.radians(destination.lat))
            * math.sin(longitude_delta / 2) ** 2
        )
        straight_line_km = 2 * 6371 * math.asin(math.sqrt(haversine))

        assert segment.distance_km >= straight_line_km
        assert segment.distance_km < straight_line_km * 1.3


def test_demo_command_prints_reference_route_json(capsys):
    main(["--origin", "A", "--destination", "C"])

    payload = json.loads(capsys.readouterr().out)

    assert payload["node_ids"] == ["A", "D", "E", "C"]
    assert payload["cost_basis"] == "reference_speed"
    assert payload["data_origin"] == "synthetic"
    assert payload["route"][0] == {"node_id": "A", "lat": -23.5505, "lon": -46.6333}
    assert payload["route"][-1] == {"node_id": "C", "lat": -23.561, "lon": -46.656}
    assert payload["distance_km"] == 5.0
    assert len(payload["route"]) == len(payload["segments"]) + 1
    assert payload["travel_time_minutes"] == sum(
        s["travel_time_minutes"] for s in payload["segments"]
    )


@pytest.mark.parametrize(
    "args,message",
    [
        (["--origin", "unknown"], "Unknown graph node"),
        (["--destination", "G"], "No directed route"),
        (["--graph", "missing.json"], "Unable to load a valid road graph"),
    ],
)
def test_demo_command_reports_controlled_failures(args, message, capsys):
    with pytest.raises(SystemExit) as exc:
        main(args)

    captured = capsys.readouterr()
    assert exc.value.code == 2
    assert message in captured.err
    assert captured.out == ""
    assert "Traceback" not in captured.err
