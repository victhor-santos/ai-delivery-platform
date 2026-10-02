from importlib.metadata import version

from fastapi import FastAPI

from app.api.health import router as health_router


def create_app() -> FastAPI:
    app = FastAPI(
        title="Route Intelligence",
        version=version("route-intelligence-service"),
        description="Internal routing service for Delivery Order System.",
    )
    app.include_router(health_router)
    return app
