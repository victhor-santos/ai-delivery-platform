import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime

from app.config import Settings
from app.routing.demo import load_demo_graph
from app.routing.graph import load_graph
from app.routing.provenance import graph_checksum
from app.routing.traffic import TrafficConfigurationError, load_demo_traffic, load_traffic
from app.services.route_planner import IncompatibleModelError, InvalidPredictionError, RoutePlanner

logger = logging.getLogger(__name__)


def utc_now() -> datetime:
    return datetime.now(UTC)


@dataclass(frozen=True)
class RoutingRuntime:
    planner: RoutePlanner | None = None
    failure_code: str | None = "MODEL_UNAVAILABLE"

    @property
    def ready(self) -> bool:
        return self.planner is not None


def load_runtime(settings: Settings, clock: Callable[[], datetime] = utc_now) -> RoutingRuntime:
    try:
        graph = load_graph(settings.graph_path) if settings.graph_path else load_demo_graph()
    except (ValueError, OSError) as exc:
        logger.warning("Unable to initialize the road graph: %s", exc)
        return RoutingRuntime(failure_code="GRAPH_UNAVAILABLE")
    if settings.model_path is None:
        logger.info("Route inference disabled: model path is not configured.")
        return RoutingRuntime()
    try:
        from app.ml.predictor import SegmentTravelTimePredictor

        predictor = SegmentTravelTimePredictor.from_directory(settings.model_path)
        if (
            predictor.metadata.graph_version != graph.graph_version
            or predictor.metadata.graph_sha256 != graph_checksum(graph)
        ):
            raise IncompatibleModelError("Loaded model and graph are incompatible.")
    except (ValueError, ImportError, OSError) as exc:
        logger.warning("Unable to initialize the route model: %s", exc)
        return RoutingRuntime()
    try:
        observed_at = clock()
        traffic = (
            load_traffic(settings.traffic_path, graph, observed_at)
            if settings.traffic_path
            else load_demo_traffic(graph, observed_at)
        )
    except (TrafficConfigurationError, OSError) as exc:
        logger.warning("Unable to initialize synthetic traffic: %s", exc)
        return RoutingRuntime(failure_code="TRAFFIC_UNAVAILABLE")
    planner = RoutePlanner(graph, predictor, traffic)
    try:
        planner.predict_costs(traffic.available_at)
    except (InvalidPredictionError, ValueError, OverflowError) as exc:
        logger.warning("Route model failed its initial prediction check: %s", exc)
        return RoutingRuntime(failure_code="INVALID_PREDICTION")
    return RoutingRuntime(planner=planner, failure_code=None)
