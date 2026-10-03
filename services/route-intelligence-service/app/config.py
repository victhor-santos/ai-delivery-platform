from pathlib import Path
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="ROUTE_INTELLIGENCE_", str_strip_whitespace=True, frozen=True
    )

    host: str = Field(default="127.0.0.1", min_length=1)
    port: int = Field(default=8000, ge=1, le=65535)
    log_level: Literal["critical", "error", "warning", "info", "debug", "trace"] = "info"
    model_path: Path | None = None
    graph_path: Path | None = None
    traffic_path: Path | None = None

    @field_validator("model_path", "graph_path", "traffic_path", mode="before")
    @classmethod
    def validate_resource_path(cls, value):
        if isinstance(value, str):
            if not value.strip():
                raise ValueError("Configured resource paths must not be empty.")
            return value.strip()
        return value
