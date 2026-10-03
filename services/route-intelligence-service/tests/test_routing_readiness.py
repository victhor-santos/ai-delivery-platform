import builtins
import json
from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.ml.predictor import SegmentTravelTimePredictor
from app.routing.demo import load_demo_graph

NOW = datetime(2026, 10, 2, 22, tzinfo=UTC)


def test_no_model_path_keeps_application_available_but_not_ready():
    with TestClient(
        create_app(Settings(model_path=None, graph_path=None, traffic_path=None))
    ) as client:
        response = client.get("/health")
        assert response.status_code == 503
        assert response.json() == {"status": "DOWN"}
        assert client.get("/openapi.json").status_code == 200


@pytest.mark.parametrize("resource", ["model", "graph", "traffic"])
def test_missing_resource_reports_down_without_aborting_startup(model_bundle, tmp_path, resource):
    configuration = {"model_path": model_bundle[0], "graph_path": None, "traffic_path": None}
    configuration[f"{resource}_path"] = tmp_path / "missing"
    with TestClient(create_app(Settings(**configuration))) as client:
        assert client.get("/health").json() == {"status": "DOWN"}
        assert client.get("/health").status_code == 503
        assert client.app.state.routing_runtime.failure_code == f"{resource.upper()}_UNAVAILABLE"


def test_same_graph_version_with_changed_attributes_is_not_ready(model_bundle, tmp_path):
    data = load_demo_graph().model_dump(mode="json")
    data["segments"][0]["reference_speed_kmh"] += 1
    path = tmp_path / "graph.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    with TestClient(
        create_app(Settings(model_path=model_bundle[0], graph_path=path, traffic_path=None))
    ) as client:
        assert client.get("/health").status_code == 503
        assert client.app.state.routing_runtime.failure_code == "MODEL_UNAVAILABLE"


def test_model_is_loaded_once_and_released_after_lifespan(model_bundle, monkeypatch):
    original = SegmentTravelTimePredictor.from_directory
    calls = []

    def load(path):
        calls.append(path)
        return original(path)

    monkeypatch.setattr(SegmentTravelTimePredictor, "from_directory", load)
    app = create_app(
        Settings(model_path=model_bundle[0], graph_path=None, traffic_path=None), clock=lambda: NOW
    )
    with TestClient(app) as client:
        for _ in range(3):
            assert client.get("/health").status_code == 200
        assert len(calls) == 1
        assert app.state.routing_runtime.planner.traffic.observed_at == NOW
    assert not app.state.routing_runtime.ready


def test_blocked_native_model_import_does_not_take_health_offline(model_bundle, monkeypatch):
    original = builtins.__import__

    def blocked(name, *args, **kwargs):
        if name == "app.ml.predictor":
            raise ImportError("native module blocked")
        return original(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", blocked)
    with TestClient(
        create_app(Settings(model_path=model_bundle[0], graph_path=None, traffic_path=None))
    ) as client:
        assert client.get("/health").status_code == 503


def test_invalid_initial_prediction_keeps_health_down(model_bundle, monkeypatch):
    def fail(self, features):
        raise ValueError("invalid model prediction")

    monkeypatch.setattr(SegmentTravelTimePredictor, "predict", fail)
    with TestClient(
        create_app(Settings(model_path=model_bundle[0], graph_path=None, traffic_path=None))
    ) as client:
        assert client.get("/health").status_code == 503
        assert client.app.state.routing_runtime.failure_code == "INVALID_PREDICTION"


@pytest.mark.parametrize("field", ["model_path", "graph_path", "traffic_path"])
def test_resource_paths_are_configurable_and_empty_paths_are_rejected(monkeypatch, field, tmp_path):
    path = tmp_path / field
    monkeypatch.setenv(f"ROUTE_INTELLIGENCE_{field.upper()}", str(path))
    assert getattr(Settings(), field) == path
    with pytest.raises(ValueError):
        Settings(**{field: " "})
