from unittest.mock import patch

import numpy as np
import pytest

from app.routing.demo import load_demo_graph
from training.load_dataset import load_dataset
from training.route_evaluation import evaluate_routes


def one_scenario(model_bundle):
    _, directory = model_bundle
    samples = load_dataset(directory).split.test
    return tuple(sample for sample in samples if sample.scenario_id == samples[0].scenario_id)


def test_observed_costs_are_used_for_oracle_only_and_not_for_predicted_route(model_bundle):
    samples = one_scenario(model_bundle)
    graph = load_demo_graph()
    actual = {s.segment_id: s.actual_travel_time_minutes for s in samples}
    predicted = np.array([100 if s.segment_id in {"A-D", "D-E", "E-C"} else 1 for s in samples])
    from app.routing.dijkstra import find_fastest_route

    with patch("training.route_evaluation.find_fastest_route", wraps=find_fastest_route) as route:
        report = evaluate_routes(graph, samples, predicted, "A", "C")
    assert route.call_args_list[0].args[3] == actual
    assert route.call_args_list[1].args[3] == {
        s.segment_id: float(p) for s, p in zip(samples, predicted, strict=True)
    }
    chosen_observed = actual["A-B"] + actual["B-C"]
    oracle = find_fastest_route(graph, "A", "C", actual)
    assert report["mean_regret_minutes"] == pytest.approx(
        chosen_observed - oracle.travel_time_minutes
    )


def test_oracle_predictions_have_zero_regret(model_bundle):
    samples = one_scenario(model_bundle)
    predicted = np.array([s.actual_travel_time_minutes for s in samples])
    report = evaluate_routes(load_demo_graph(), samples, predicted, "A", "C")
    assert report["mean_regret_minutes"] == 0
    assert report["optimal_observed_time_fraction"] == 1


def test_equivalent_oracle_routes_have_exactly_zero_regret_across_all_scenarios(model_bundle):
    _, directory = model_bundle
    samples = load_dataset(directory).split.test
    predictions = np.array([sample.actual_travel_time_minutes for sample in samples])
    report = evaluate_routes(load_demo_graph(), samples, predictions, "A", "C")
    assert report["optimal_observed_time_fraction"] == 1
    assert report["mean_regret_minutes"] == 0
    assert report["p95_regret_minutes"] == 0


def test_invalid_scenario_is_counted_and_not_reported_as_zero_regret(model_bundle):
    samples = one_scenario(model_bundle)
    predictions = np.ones(len(samples))
    predictions[0] = np.nan
    report = evaluate_routes(load_demo_graph(), samples, predictions, "A", "C")
    assert report["invalid_prediction_scenarios"] == 1
    assert report["evaluated_scenarios"] == 0
    assert report["mean_regret_minutes"] is None


def test_partial_graph_snapshot_cannot_be_evaluated(model_bundle):
    samples = one_scenario(model_bundle)[:-1]
    with pytest.raises(ValueError, match="complete graph"):
        evaluate_routes(load_demo_graph(), samples, np.ones(len(samples)), "A", "C")
