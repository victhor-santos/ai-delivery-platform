import json
import math
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.routing.coverage import EARTH_RADIUS_METERS
from app.routing.demo import load_demo_graph

NOW = datetime(2026, 10, 2, 22, tzinfo=UTC)


@pytest.fixture
def body():
    graph = load_demo_graph()
    return {
        "origin": {"lat": graph.nodes_by_id["A"].lat, "lon": graph.nodes_by_id["A"].lon},
        "destination": {"lat": graph.nodes_by_id["C"].lat, "lon": graph.nodes_by_id["C"].lon},
        "departure_at": "2026-09-29T19:00:00-03:00",
    }


@pytest.fixture
def client(model_bundle):
    settings = Settings(model_path=model_bundle[0], graph_path=None, traffic_path=None)
    with TestClient(
        create_app(settings, clock=lambda: NOW), raise_server_exceptions=False
    ) as client:
        yield client


def assert_problem(response, status, code):
    assert response.status_code == status
    assert response.headers["content-type"] == "application/problem+json"
    problem = response.json()
    assert set(problem) == {"type", "title", "status", "detail", "code"}
    assert problem["type"] == "about:blank"
    assert problem["status"] == status
    assert problem["code"] == code


def test_http_route_uses_real_model_and_ordered_segment_totals(client, body):
    response = client.post("/api/routes/fastest", json=body)
    assert response.status_code == 200
    route = response.json()
    assert route["route"][0] == body["origin"]
    assert route["route"][-1] == body["destination"]
    assert len(route["route"]) == len(route["segments"]) + 1
    assert route["distance_km"] == pytest.approx(sum(s["distance_km"] for s in route["segments"]))
    assert route["predicted_travel_time_minutes"] == pytest.approx(
        sum(s["predicted_travel_time_minutes"] for s in route["segments"])
    )
    assert (
        route["model_version"]
        == client.app.state.routing_runtime.planner.predictor.metadata.model_version
    )
    assert route["data_origin"] == "synthetic"
    assert route["predicted_at"] == route["context_as_of"] == "2026-10-02T22:00:00Z"
    assert all(s["predicted_travel_time_minutes"] > 0 for s in route["segments"])
    assert "dataset_id" not in route


def test_submeter_offset_returns_node_coordinates(client, body):
    original = dict(body["origin"])
    body["origin"]["lat"] += math.degrees(0.5 / EARTH_RADIUS_METERS)
    response = client.post("/api/routes/fastest", json=body)
    assert response.status_code == 200
    assert response.json()["route"][0] == original


def test_origin_equal_to_destination_returns_single_coordinate_and_zero_cost(client, body):
    body["destination"] = body["origin"]
    route = client.post("/api/routes/fastest", json=body).json()
    assert route["route"] == [body["origin"]]
    assert route["segments"] == []
    assert route["distance_km"] == route["predicted_travel_time_minutes"] == 0


@pytest.mark.parametrize("mutation", ["extra", "naive", "unix", "bool", "missing", "infinity"])
def test_invalid_requests_are_problem_json(client, body, mutation):
    if mutation == "extra":
        body["traffic_level"] = "low"
    elif mutation == "naive":
        body["departure_at"] = "2026-09-29T19:00:00"
    elif mutation == "unix":
        body["departure_at"] = 1790719200
    elif mutation == "bool":
        body["origin"]["lat"] = True
    elif mutation == "missing":
        body.pop("destination")
    else:
        body["origin"]["lat"] = float("inf")
    response = client.post(
        "/api/routes/fastest",
        content=json.dumps(body),
        headers={"content-type": "application/json"},
    )
    assert_problem(response, 422, "INVALID_REQUEST")


def test_malformed_json_does_not_expose_validation_details(client):
    response = client.post(
        "/api/routes/fastest", content='{"private":', headers={"content-type": "application/json"}
    )
    assert_problem(response, 422, "INVALID_REQUEST")
    assert "private" not in response.text


def test_outside_coverage_and_disconnected_graph_have_distinct_errors(client, body):
    body["origin"] = {"lat": 0, "lon": 0}
    assert_problem(client.post("/api/routes/fastest", json=body), 422, "OUTSIDE_GRAPH_COVERAGE")
    isolated = load_demo_graph().nodes_by_id["G"]
    body["origin"] = {"lat": isolated.lat, "lon": isolated.lon}
    assert_problem(client.post("/api/routes/fastest", json=body), 404, "ROUTE_NOT_FOUND")


@pytest.mark.parametrize("resource", ["model", "graph", "traffic"])
def test_unavailable_resources_do_not_leak_paths(model_bundle, tmp_path, body, resource):
    settings = {"model_path": model_bundle[0], "graph_path": None, "traffic_path": None}
    settings[f"{resource}_path"] = tmp_path / "private-secret-resource"
    with TestClient(create_app(Settings(**settings))) as client:
        response = client.post("/api/routes/fastest", json=body)
        assert_problem(response, 503, f"{resource.upper()}_UNAVAILABLE")
        assert "private-secret-resource" not in response.text


def test_invalid_prediction_is_not_replaced_by_reference_route(client, body, monkeypatch):
    predictor = client.app.state.routing_runtime.planner.predictor
    monkeypatch.setattr(predictor, "predict", lambda _: (float("nan"),) * 10)
    assert_problem(client.post("/api/routes/fastest", json=body), 503, "INVALID_PREDICTION")


def test_unexpected_failure_returns_safe_internal_problem(client, body, monkeypatch):
    predictor = client.app.state.routing_runtime.planner.predictor

    def fail(_):
        raise RuntimeError("private filesystem path or internal details")

    monkeypatch.setattr(predictor, "predict", fail)
    response = client.post("/api/routes/fastest", json=body)
    assert_problem(response, 500, "INTERNAL_ERROR")
    assert "private filesystem" not in response.text


def test_clock_before_snapshot_does_not_use_future_context(client, body):
    client.app.state.clock = lambda: NOW - timedelta(seconds=1)
    assert_problem(client.post("/api/routes/fastest", json=body), 503, "TRAFFIC_UNAVAILABLE")


def test_concurrent_requests_share_loaded_model_and_keep_snapshots_consistent(client, body):
    def calculate(_):
        response = client.post("/api/routes/fastest", json=body)
        assert response.status_code == 200
        return response.json()

    with ThreadPoolExecutor(max_workers=4) as pool:
        responses = list(pool.map(calculate, range(8)))
    assert all(response == responses[0] for response in responses)


def test_openapi_documents_route_and_problem_contract(client):
    schema = client.get("/openapi.json").json()
    operation = schema["paths"]["/api/routes/fastest"]["post"]
    assert set(operation["responses"]) == {"200", "404", "422", "500", "503"}
    for status in ("404", "422", "500", "503"):
        assert set(operation["responses"][status]["content"]) == {"application/problem+json"}
    assert schema["components"]["schemas"]["RouteRequest"]["additionalProperties"] is False
