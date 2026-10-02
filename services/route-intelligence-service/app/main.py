from importlib.metadata import version

from fastapi import FastAPI


def create_app() -> FastAPI:
    return FastAPI(
        title="Route Intelligence",
        version=version("route-intelligence-service"),
        description="Internal routing service for Delivery Order System.",
    )
