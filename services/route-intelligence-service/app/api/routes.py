from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.api.problems import ProblemResponse, problem_response
from app.routing.coverage import OutsideGraphCoverageError
from app.routing.dijkstra import RouteNotFoundError
from app.schemas.routes import RouteRequest, RouteResponse
from app.services.route_planner import InvalidPredictionError, TrafficUnavailableError

router = APIRouter(prefix="/api/routes", tags=["routes"])
ERROR_RESPONSES = {
    status: {
        "content": {"application/problem+json": {"schema": ProblemResponse.model_json_schema()}},
    }
    for status in (404, 422, 500, 503)
}


@router.post("/fastest", response_model=RouteResponse, responses=ERROR_RESPONSES)
def fastest_route(body: RouteRequest, request: Request) -> RouteResponse | JSONResponse:
    runtime = request.app.state.routing_runtime
    if not runtime.ready:
        return problem_response(runtime.failure_code or "MODEL_UNAVAILABLE")
    try:
        return runtime.planner.plan(body, request.app.state.clock())
    except OutsideGraphCoverageError:
        return problem_response("OUTSIDE_GRAPH_COVERAGE")
    except RouteNotFoundError:
        return problem_response("ROUTE_NOT_FOUND")
    except InvalidPredictionError:
        return problem_response("INVALID_PREDICTION")
    except TrafficUnavailableError:
        return problem_response("TRAFFIC_UNAVAILABLE")
