from collections.abc import Callable
from contextlib import asynccontextmanager
from datetime import datetime
from importlib.metadata import version

from fastapi import FastAPI
from starlette.concurrency import run_in_threadpool

from app.api.health import router as health_router
from app.config import Settings
from app.services.runtime import RoutingRuntime, load_runtime, utc_now


def create_app(
    settings: Settings | None = None, *, clock: Callable[[], datetime] = utc_now
) -> FastAPI:
    configuration = settings if settings is not None else Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.routing_runtime = await run_in_threadpool(load_runtime, configuration, clock)
        try:
            yield
        finally:
            app.state.routing_runtime = RoutingRuntime()

    app = FastAPI(
        title="Route Intelligence",
        version=version("route-intelligence-service"),
        description="Internal routing service for Delivery Order System.",
        lifespan=lifespan,
    )
    app.state.routing_runtime = RoutingRuntime()
    app.state.clock = clock
    app.include_router(health_router)
    return app
