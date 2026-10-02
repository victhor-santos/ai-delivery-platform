import argparse
import json
from dataclasses import asdict
from pathlib import Path

from app.routing.demo import load_demo_graph
from app.routing.dijkstra import (
    InvalidRouteCostsError,
    NodeNotFoundError,
    RouteNotFoundError,
    find_fastest_route,
    reference_travel_times,
)
from app.routing.graph import GraphLoadError, load_graph


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Route on a synthetic graph using reference speeds."
    )
    parser.add_argument(
        "--graph", type=Path, help="Graph JSON; defaults to packaged synthetic-city-v1."
    )
    parser.add_argument("--origin", default="A", help="Origin node identifier (default: A).")
    parser.add_argument(
        "--destination", default="C", help="Destination node identifier (default: C)."
    )
    args = parser.parse_args(argv)
    try:
        graph = load_graph(args.graph) if args.graph is not None else load_demo_graph()
        route = find_fastest_route(
            graph, args.origin, args.destination, reference_travel_times(graph)
        )
    except (GraphLoadError, NodeNotFoundError, RouteNotFoundError, InvalidRouteCostsError) as exc:
        parser.error(str(exc))
    nodes = graph.nodes_by_id
    print(
        json.dumps(
            {
                "graph_version": route.graph_version,
                "data_origin": graph.data_origin,
                "vehicle_profile": graph.vehicle_profile,
                "timezone": graph.timezone,
                "cost_basis": "reference_speed",
                "node_ids": route.node_ids,
                "route": [nodes[node_id].model_dump() for node_id in route.node_ids],
                "segments": [asdict(segment) for segment in route.segments],
                "distance_km": route.distance_km,
                "travel_time_minutes": route.travel_time_minutes,
            },
            indent=2,
            allow_nan=False,
        )
    )


if __name__ == "__main__":
    main()
