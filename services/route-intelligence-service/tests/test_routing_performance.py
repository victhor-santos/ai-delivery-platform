from time import perf_counter

from app.routing.demo import load_demo_graph
from app.routing.dijkstra import find_fastest_route, reference_travel_times
from app.routing.graph import MAX_NODES, MAX_SEGMENTS, RoadGraph


def test_demo_routing_batch_fits_reference_budget():
    graph = load_demo_graph()
    costs = reference_travel_times(graph)
    started = perf_counter()

    for _ in range(1000):
        route = find_fastest_route(graph, "A", "C", costs)

    elapsed = perf_counter() - started
    assert route.node_ids == ("A", "D", "E", "C")
    assert elapsed < 3.0
    print(f"demo: 1000 routes in {elapsed:.4f}s")


def test_graph_at_supported_limits_fits_reference_budget(graph_data):
    graph_data["nodes"] = [{"node_id": f"N{i:03}", "lat": 0, "lon": 0} for i in range(MAX_NODES)]
    graph_data["segments"] = [
        {
            "segment_id": f"{origin}-{step}",
            "from_node": f"N{origin:03}",
            "to_node": f"N{(origin + step) % MAX_NODES:03}",
            "distance_km": 1,
            "road_type": "primary",
            "reference_speed_kmh": 60,
        }
        for origin in range(MAX_NODES)
        for step in range(1, 6)
    ]
    graph = RoadGraph.model_validate(graph_data)
    assert len(graph.segments) == MAX_SEGMENTS
    costs = reference_travel_times(graph)
    started = perf_counter()

    for _ in range(100):
        route = find_fastest_route(graph, "N000", "N199", costs)

    elapsed = perf_counter() - started
    assert route.distance_km == 40
    assert route.travel_time_minutes == 40
    assert elapsed < 3.0
    print(f"limits: 100 routes in {elapsed:.4f}s")
