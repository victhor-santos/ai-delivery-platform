import math
from collections import defaultdict
from collections.abc import Sequence

import numpy as np

from app.routing.dijkstra import find_fastest_route
from app.routing.graph import RoadGraph
from training.schema import SegmentSample


def evaluate_routes(
    graph: RoadGraph,
    samples: Sequence[SegmentSample],
    predictions: np.ndarray,
    origin: str,
    destination: str,
) -> dict:
    if origin == destination:
        raise ValueError("Route evaluation requires distinct origin and destination nodes.")
    if predictions.shape != (len(samples),):
        raise ValueError("Route evaluation requires one prediction per observation.")
    groups = defaultdict(list)
    for sample, prediction in zip(samples, predictions, strict=True):
        groups[sample.scenario_id].append((sample, float(prediction)))
    regrets, excess_percent, selected_times, oracle_times = [], [], [], []
    optimal = invalid = 0
    graph_segments = graph.segments_by_id
    for rows in groups.values():
        if len(rows) != len(graph_segments) or {s.segment_id for s, _ in rows} != set(
            graph_segments
        ):
            raise ValueError("Route evaluation requires a complete graph snapshot per scenario.")
        if len({s.planned_departure_at for s, _ in rows}) != 1:
            raise ValueError("Scenario features must describe one shared planned departure.")
        for sample, _ in rows:
            segment = graph_segments[sample.segment_id]
            if (
                sample.graph_version != graph.graph_version
                or sample.from_node != segment.from_node
                or sample.to_node != segment.to_node
                or sample.distance_km != segment.distance_km
                or sample.reference_speed_kmh != segment.reference_speed_kmh
                or sample.road_type != segment.road_type
            ):
                raise ValueError("Observation segment attributes differ from the evaluation graph.")
        observed = {s.segment_id: s.actual_travel_time_minutes for s, _ in rows}
        oracle = find_fastest_route(graph, origin, destination, observed)
        if any(not math.isfinite(value) or value <= 0 for _, value in rows):
            invalid += 1
            continue
        predicted = {s.segment_id: value for s, value in rows}
        route = find_fastest_route(graph, origin, destination, predicted)
        selected_time = sum(observed[segment.segment_id] for segment in route.segments)
        equivalent = math.isclose(
            selected_time, oracle.travel_time_minutes, rel_tol=1e-9, abs_tol=1e-9
        )
        regret = 0.0 if equivalent else selected_time - oracle.travel_time_minutes
        optimal += equivalent
        regrets.append(regret)
        excess_percent.append(regret / oracle.travel_time_minutes * 100)
        selected_times.append(selected_time)
        oracle_times.append(oracle.travel_time_minutes)
    return {
        "origin": origin,
        "destination": destination,
        "scenarios": len(groups),
        "evaluated_scenarios": len(regrets),
        "invalid_prediction_scenarios": invalid,
        "optimal_observed_time_fraction": optimal / len(regrets) if regrets else None,
        "mean_regret_minutes": float(np.mean(regrets)) if regrets else None,
        "p95_regret_minutes": float(np.percentile(regrets, 95)) if regrets else None,
        "mean_excess_percent": float(np.mean(excess_percent)) if regrets else None,
        "mean_selected_observed_minutes": float(np.mean(selected_times)) if regrets else None,
        "mean_oracle_observed_minutes": float(np.mean(oracle_times)) if regrets else None,
        "observed_costs_used_only_for_evaluation": True,
    }
