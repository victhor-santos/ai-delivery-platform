import logging
from typing import Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

logger = logging.getLogger(__name__)
ProblemCode = Literal[
    "INVALID_REQUEST",
    "OUTSIDE_GRAPH_COVERAGE",
    "ROUTE_NOT_FOUND",
    "MODEL_UNAVAILABLE",
    "GRAPH_UNAVAILABLE",
    "TRAFFIC_UNAVAILABLE",
    "INVALID_PREDICTION",
    "INTERNAL_ERROR",
]
PROBLEMS = {
    "INVALID_REQUEST": (
        422,
        "Invalid request",
        "Request body does not satisfy the route contract.",
    ),
    "OUTSIDE_GRAPH_COVERAGE": (
        422,
        "Outside graph coverage",
        "Coordinates are outside the synthetic graph coverage.",
    ),
    "ROUTE_NOT_FOUND": (
        404,
        "Route not found",
        "No directed route connects the requested locations.",
    ),
    "MODEL_UNAVAILABLE": (
        503,
        "Route service unavailable",
        "Route calculation is temporarily unavailable.",
    ),
    "GRAPH_UNAVAILABLE": (
        503,
        "Route service unavailable",
        "Route calculation is temporarily unavailable.",
    ),
    "TRAFFIC_UNAVAILABLE": (
        503,
        "Route service unavailable",
        "Route calculation is temporarily unavailable.",
    ),
    "INVALID_PREDICTION": (503, "Invalid prediction", "Model did not provide valid route costs."),
    "INTERNAL_ERROR": (500, "Internal error", "Route calculation failed unexpectedly."),
}


class ProblemResponse(BaseModel):
    type: Literal["about:blank"] = "about:blank"
    title: str
    status: int
    detail: str
    code: ProblemCode


def problem_response(code: str) -> JSONResponse:
    if code not in PROBLEMS:
        code = "INTERNAL_ERROR"
    status, title, detail = PROBLEMS[code]
    problem = ProblemResponse(title=title, status=status, detail=detail, code=code)
    return JSONResponse(
        problem.model_dump(mode="json"), status_code=status, media_type="application/problem+json"
    )


def register_problem_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exception: RequestValidationError):
        return problem_response("INVALID_REQUEST")

    @app.exception_handler(Exception)
    async def internal_error(request: Request, exception: Exception):
        logger.exception("Unhandled route service error", exc_info=exception)
        return problem_response("INTERNAL_ERROR")
