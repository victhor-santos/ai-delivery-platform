from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from app.ml.features import features_for_segment
from app.routing.coordinates import GeoPoint
from app.routing.coverage import match_node
from app.routing.dijkstra import InvalidRouteCostsError, find_fastest_route
from app.routing.graph import RoadGraph
from app.routing.provenance import graph_checksum
from app.routing.traffic import SyntheticTrafficSnapshot
from app.schemas.routes import RouteRequest, RouteResponse, SegmentResponse

if TYPE_CHECKING:
    from app.ml.predictor import SegmentTravelTimePredictor


class IncompatibleModelError(ValueError):
    """Model metadata does not identify this graph."""


class InvalidPredictionError(ValueError):
    """The predictor did not provide usable costs for the whole graph."""


class TrafficUnavailableError(ValueError):
    """The configured traffic was not available at the prediction time."""


@dataclass(frozen=True)
class RoutePlanner:
    graph: RoadGraph
    predictor: SegmentTravelTimePredictor
    traffic: SyntheticTrafficSnapshot

    def __post_init__(self) -> None:
        metadata = self.predictor.metadata
        if (
            metadata.graph_version != self.graph.graph_version
            or metadata.graph_sha256 != graph_checksum(self.graph)
        ):
            raise IncompatibleModelError("Model metadata does not match the loaded graph.")

    def predict_costs(self, departure_at: datetime) -> dict[str, float]:
        segments = sorted(self.graph.segments, key=lambda segment: segment.segment_id)
        if not segments:
            return {}
        features = [
            features_for_segment(segment, self.traffic.levels[segment.segment_id], departure_at)
            for segment in segments
        ]
        try:
            predictions = self.predictor.predict(features)
            return dict(zip((s.segment_id for s in segments), predictions, strict=True))
        except (ValueError, OverflowError, FloatingPointError) as exc:
            raise InvalidPredictionError(
                "Model did not produce valid segment travel times."
            ) from exc

    def plan(self, request: RouteRequest, predicted_at: datetime) -> RouteResponse:
        if predicted_at.tzinfo is None or predicted_at.utcoffset() is None:
            raise ValueError("Prediction clock must be timezone-aware.")
        timestamp = predicted_at.astimezone(UTC)
        if self.traffic.available_at > timestamp:
            raise TrafficUnavailableError("Traffic is not yet available for this prediction.")
        origin = match_node(self.graph, request.origin)
        destination = match_node(self.graph, request.destination)
        metadata = {
            "predicted_at": timestamp,
            "context_as_of": self.traffic.available_at,
            "model_version": self.predictor.metadata.model_version,
            "graph_version": self.graph.graph_version,
        }
        if origin.node_id == destination.node_id:
            return RouteResponse(
                route=(GeoPoint(lat=origin.lat, lon=origin.lon),),
                segments=(),
                distance_km=0,
                predicted_travel_time_minutes=0,
                **metadata,
            )
        try:
            route = find_fastest_route(
                self.graph,
                origin.node_id,
                destination.node_id,
                self.predict_costs(request.departure_at),
            )
        except InvalidRouteCostsError as exc:
            raise InvalidPredictionError("Predicted route costs are invalid.") from exc
        nodes = self.graph.nodes_by_id
        return RouteResponse(
            route=tuple(
                GeoPoint(lat=nodes[node].lat, lon=nodes[node].lon) for node in route.node_ids
            ),
            segments=tuple(
                SegmentResponse(
                    segment_id=segment.segment_id,
                    distance_km=segment.distance_km,
                    predicted_travel_time_minutes=segment.travel_time_minutes,
                )
                for segment in route.segments
            ),
            distance_km=route.distance_km,
            predicted_travel_time_minutes=route.travel_time_minutes,
            **metadata,
        )
