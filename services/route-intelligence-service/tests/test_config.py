import pytest
from pydantic import ValidationError

from app.config import Settings


@pytest.fixture(autouse=True)
def clear_service_environment(monkeypatch):
    for name in ("HOST", "PORT", "LOG_LEVEL"):
        monkeypatch.delenv(f"ROUTE_INTELLIGENCE_{name}", raising=False)


def test_defaults_bind_to_localhost():
    settings = Settings()

    assert settings.host == "127.0.0.1"
    assert settings.port == 8000
    assert settings.log_level == "info"


def test_environment_overrides_defaults(monkeypatch):
    monkeypatch.setenv("ROUTE_INTELLIGENCE_HOST", "0.0.0.0")
    monkeypatch.setenv("ROUTE_INTELLIGENCE_PORT", "8100")
    monkeypatch.setenv("ROUTE_INTELLIGENCE_LOG_LEVEL", "warning")

    settings = Settings()

    assert settings.host == "0.0.0.0"
    assert settings.port == 8100
    assert settings.log_level == "warning"


@pytest.mark.parametrize("port", ["0", "-1", "65536", "not-a-port", "8000.5"])
def test_invalid_environment_port_is_rejected(monkeypatch, port):
    monkeypatch.setenv("ROUTE_INTELLIGENCE_PORT", port)

    with pytest.raises(ValidationError):
        Settings()


@pytest.mark.parametrize("port", ["1", "65535"])
def test_port_boundaries_are_accepted(monkeypatch, port):
    monkeypatch.setenv("ROUTE_INTELLIGENCE_PORT", port)

    assert Settings().port == int(port)


def test_blank_host_is_rejected(monkeypatch):
    monkeypatch.setenv("ROUTE_INTELLIGENCE_HOST", "   ")

    with pytest.raises(ValidationError):
        Settings()


def test_invalid_log_level_is_rejected(monkeypatch):
    monkeypatch.setenv("ROUTE_INTELLIGENCE_LOG_LEVEL", "verbose")

    with pytest.raises(ValidationError):
        Settings()
