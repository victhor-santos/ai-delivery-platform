import pytest

from app.schemas.routes import RouteRequest


@pytest.fixture
def route_request_body():
    return {
        "origin": {"lat": -23.5505, "lon": -46.6333},
        "destination": {"lat": -23.5610, "lon": -46.6560},
        "departure_at": "2026-09-29T19:00:00-03:00",
    }


def test_request_normalizes_departure_to_utc(route_request_body):
    request = RouteRequest.model_validate(route_request_body)
    assert request.departure_at.isoformat() == "2026-09-29T22:00:00+00:00"


@pytest.mark.parametrize(
    "value",
    [
        "2026-09-29T19:00:00",
        "2026-09-29 19:00:00-03:00",
        "2026-09-29",
        1790719200,
        "1790719200",
        True,
        "9999-12-31T23:59:59-03:00",
        "0001-01-01T00:00:00Z",
    ],
)
def test_departure_requires_supported_rfc3339_with_offset(route_request_body, value):
    with pytest.raises(ValueError):
        RouteRequest.model_validate(dict(route_request_body, departure_at=value))


@pytest.mark.parametrize(
    "point",
    [
        {"lat": True, "lon": 0},
        {"lat": "0", "lon": 0},
        {"lat": 91, "lon": 0},
        {"lat": 0, "lon": float("nan")},
        {"lat": 0, "lon": 0, "node_id": "A"},
    ],
)
def test_coordinates_are_strict_and_finite(route_request_body, point):
    with pytest.raises(ValueError):
        RouteRequest.model_validate(dict(route_request_body, origin=point))


def test_client_cannot_provide_traffic_or_features(route_request_body):
    with pytest.raises(ValueError):
        RouteRequest.model_validate(dict(route_request_body, traffic_level="low"))
