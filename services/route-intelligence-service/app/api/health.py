from typing import Literal

from fastapi import APIRouter, Request, Response
from pydantic import BaseModel

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: Literal["UP", "DOWN"]


@router.get("/health", response_model=HealthResponse, responses={503: {"model": HealthResponse}})
async def health(request: Request, response: Response) -> HealthResponse:
    ready = request.app.state.routing_runtime.ready
    response.status_code = 200 if ready else 503
    return HealthResponse(status="UP" if ready else "DOWN")
