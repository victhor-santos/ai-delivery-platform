import logging
import re

import pytest
from fastapi.testclient import TestClient

from app.api.request_ids import RequestIdLogFilter
from app.config import Settings
from app.main import create_app
from app.routing.demo import load_demo_graph

GENERATED = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


@pytest.fixture
def client(model_bundle):
    settings = Settings(model_path=model_bundle[0], graph_path=None, traffic_path=None)
    with TestClient(create_app(settings), raise_server_exceptions=False) as client:
        yield client


@pytest.fixture
def body():
    graph = load_demo_graph()
    return {
        "origin": {"lat": graph.nodes_by_id["A"].lat, "lon": graph.nodes_by_id["A"].lon},
        "destination": {"lat": graph.nodes_by_id["C"].lat, "lon": graph.nodes_by_id["C"].lon},
        "departure_at": "2026-09-29T19:00:00-03:00",
    }


class Records(logging.Handler):
    def __init__(self):
        super().__init__()
        self.addFilter(RequestIdLogFilter())
        self.records: list[logging.LogRecord] = []

    def emit(self, record):
        self.records.append(record)


def test_valid_request_id_is_returned_and_logged(client, body):
    handler = Records()
    logger = logging.getLogger("app.requests")
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    try:
        response = client.post(
            "/api/routes/fastest", json=body, headers={"X-Request-Id": "web-0123456789abcdef"}
        )
    finally:
        logger.removeHandler(handler)

    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "web-0123456789abcdef"
    [record] = handler.records
    assert record.request_id == "web-0123456789abcdef"
    assert record.getMessage().startswith("method=POST path=/api/routes/fastest status=200 ")


@pytest.mark.parametrize("invalid", ["short", "bad id;", "x" * 65])
def test_invalid_request_id_is_replaced(client, invalid):
    response = client.get("/health", headers={"X-Request-Id": invalid})

    assert GENERATED.fullmatch(response.headers["X-Request-Id"])


def test_unexpected_failure_keeps_the_request_id(client, body, monkeypatch):
    predictor = client.app.state.routing_runtime.planner.predictor

    def fail(_):
        raise RuntimeError("internal details")

    monkeypatch.setattr(predictor, "predict", fail)
    response = client.post(
        "/api/routes/fastest", json=body, headers={"X-Request-Id": "web-0123456789abcdef"}
    )

    assert response.status_code == 500
    assert response.headers["X-Request-Id"] == "web-0123456789abcdef"
