from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.ml.features import SegmentFeatures, departure_context
from app.ml.predictor import SegmentTravelTimePredictor
from app.routing.demo import load_demo_graph
from app.routing.dijkstra import RouteNotFoundError
from app.routing.provenance import graph_checksum
from app.routing.traffic import SyntheticTrafficScenario, load_demo_traffic, traffic_snapshot
from app.schemas.routes import RouteRequest
from app.services.route_planner import (
    IncompatibleModelError,
    InvalidPredictionError,
    RoutePlanner,
    TrafficUnavailableError,
)

NOW = datetime(2026, 10, 2, 22, tzinfo=UTC)


class ReferencePredictor:
    def __init__(self, graph):
        self.metadata = SimpleNamespace(
            model_version="test-model-v1",
            graph_version=graph.graph_version,
            graph_sha256=graph_checksum(graph),
        )
        self.calls = []

    def predict(self, features):
        self.calls.append(features)
        return tuple(
            f.distance_km / f.reference_speed_kmh * 60 * (10 if f.traffic_level == "high" else 1)
            for f in features
        )


def request(graph, origin="A", destination="C"):
    nodes = graph.nodes_by_id
    return RouteRequest.model_validate(
        {
            "origin": {"lat": nodes[origin].lat, "lon": nodes[origin].lon},
            "destination": {"lat": nodes[destination].lat, "lon": nodes[destination].lon},
            "departure_at": "2026-09-29T19:00:00-03:00",
        }
    )


def test_predicts_every_segment_once_with_shared_departure_context():
    graph = load_demo_graph()
    predictor = ReferencePredictor(graph)
    traffic = load_demo_traffic(graph, NOW)
    planner = RoutePlanner(graph, predictor, traffic)
    body = request(graph)
    route = planner.plan(body, NOW + timedelta(seconds=1))
    assert [s.segment_id for s in route.segments] == ["A-B", "B-C"]
    assert route.distance_km == 2.9
    assert len(predictor.calls) == 1
    assert len(predictor.calls[0]) == len(graph.segments)
    assert {(f.hour, f.day_of_week) for f in predictor.calls[0]} == {
        departure_context(body.departure_at)
    }
    assert route.context_as_of == NOW < route.predicted_at
    features_by_id = dict(zip(sorted(graph.segments_by_id), predictor.calls[0], strict=True))
    for segment in route.segments:
        context = segment.prediction_context
        matching = features_by_id[segment.segment_id]
        assert (
            context.model_dump(include=set(SegmentFeatures.model_fields)) == matching.model_dump()
        )
        edge = graph.segments_by_id[segment.segment_id]
        assert (context.from_node, context.to_node) == (edge.from_node, edge.to_node)
        assert context.traffic_source == traffic.source
        assert context.traffic_observed_at == context.traffic_available_at == NOW
        assert context.features_available_at <= route.context_as_of <= route.predicted_at
    assert route.predicted_travel_time_minutes == sum(
        s.predicted_travel_time_minutes for s in route.segments
    )


def test_prediction_snapshot_retains_original_features_after_later_planning():
    graph = load_demo_graph()
    traffic = load_demo_traffic(graph, NOW)
    planner = RoutePlanner(graph, ReferencePredictor(graph), traffic)
    first = planner.plan(request(graph), NOW)
    original = first.model_dump(mode="json")
    later_request = request(graph).model_copy(update={"departure_at": NOW + timedelta(hours=1)})
    later = planner.plan(later_request, NOW + timedelta(minutes=1))
    assert first.model_dump(mode="json") == original
    assert first.segments[0].prediction_context.hour != later.segments[0].prediction_context.hour
    assert first.segments[0].prediction_context.feature_schema_version == "segment-features-v1"


def test_low_traffic_selects_longer_route_that_is_faster_in_time():
    graph = load_demo_graph()
    traffic = traffic_snapshot(
        SyntheticTrafficScenario(
            scenario_version="low-v1",
            graph_version=graph.graph_version,
            data_origin="synthetic",
            levels={segment.segment_id: "low" for segment in graph.segments},
        ),
        graph,
        NOW,
    )
    route = RoutePlanner(graph, ReferencePredictor(graph), traffic).plan(request(graph), NOW)
    assert [s.segment_id for s in route.segments] == ["A-D", "D-E", "E-C"]
    assert route.distance_km == 5


def test_same_node_returns_zero_cost_without_prediction():
    graph = load_demo_graph()
    predictor = ReferencePredictor(graph)
    route = RoutePlanner(graph, predictor, load_demo_traffic(graph, NOW)).plan(
        request(graph, "G", "G"), NOW
    )
    assert len(route.route) == 1
    assert not route.segments
    assert route.distance_km == route.predicted_travel_time_minutes == 0
    assert predictor.calls == []


def test_disconnected_nodes_have_no_directed_route():
    graph = load_demo_graph()
    with pytest.raises(RouteNotFoundError):
        RoutePlanner(graph, ReferencePredictor(graph), load_demo_traffic(graph, NOW)).plan(
            request(graph, "A", "G"), NOW
        )


@pytest.mark.parametrize(
    "field, value", [("graph_version", "another-v1"), ("graph_sha256", "a" * 64)]
)
def test_same_name_or_schema_does_not_override_graph_provenance(field, value):
    graph = load_demo_graph()
    predictor = ReferencePredictor(graph)
    setattr(predictor.metadata, field, value)
    with pytest.raises(IncompatibleModelError):
        RoutePlanner(graph, predictor, load_demo_traffic(graph, NOW))


def test_future_traffic_is_not_used_for_prediction():
    graph = load_demo_graph()
    predictor = ReferencePredictor(graph)
    planner = RoutePlanner(graph, predictor, load_demo_traffic(graph, NOW + timedelta(seconds=1)))
    with pytest.raises(TrafficUnavailableError):
        planner.plan(request(graph), NOW)
    assert predictor.calls == []


def test_invalid_predictions_are_not_replaced_by_reference_costs(monkeypatch):
    graph = load_demo_graph()
    predictor = ReferencePredictor(graph)
    monkeypatch.setattr(predictor, "predict", lambda _: (float("nan"),) * len(graph.segments))
    with pytest.raises(InvalidPredictionError):
        RoutePlanner(graph, predictor, load_demo_traffic(graph, NOW)).plan(request(graph), NOW)


def test_real_predictor_and_graph_produce_a_consistent_route(model_bundle):
    artifact, _ = model_bundle
    graph = load_demo_graph()
    planner = RoutePlanner(
        graph, SegmentTravelTimePredictor.from_directory(artifact), load_demo_traffic(graph, NOW)
    )
    response = planner.plan(request(graph), NOW)
    assert len(response.route) == len(response.segments) + 1
    assert response.distance_km == pytest.approx(sum(s.distance_km for s in response.segments))
    assert response.predicted_travel_time_minutes > 0
    assert response.model_version == planner.predictor.metadata.model_version
