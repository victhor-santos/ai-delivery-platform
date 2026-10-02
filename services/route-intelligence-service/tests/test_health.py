import pytest
from fastapi.testclient import TestClient

from app.main import create_app


@pytest.fixture
def client():
    with TestClient(create_app()) as client:
        yield client


def test_health_reports_application_availability(client):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.headers["content-type"] == "application/json"
    assert response.json() == {"status": "UP"}


def test_health_remains_available_on_repeated_requests(client):
    for _ in range(3):
        response = client.get("/health")

        assert response.status_code == 200
        assert response.json() == {"status": "UP"}


def test_openapi_publishes_the_health_contract(client):
    response = client.get("/openapi.json")

    assert response.status_code == 200
    schema = response.json()
    assert schema["info"]["title"] == "Route Intelligence"
    assert schema["info"]["version"] == "0.1.0"
    health = schema["paths"]["/health"]["get"]
    assert "200" in health["responses"]
    response_schema = health["responses"]["200"]["content"]["application/json"]["schema"]
    assert response_schema["$ref"] == "#/components/schemas/HealthResponse"
    status_schema = schema["components"]["schemas"]["HealthResponse"]["properties"]["status"]
    assert status_schema["const"] == "UP"
