import math
from datetime import UTC
from typing import Annotated, Literal, Self

from pydantic import AfterValidator, AwareDatetime, model_validator

from app.ml.features import SegmentFeatures, departure_context
from app.routing.graph import Identifier, PositiveNumber

GENERATOR_VERSION = "synthetic-segments-v1"
SAMPLE_SCHEMA_VERSION = "segment-sample-v1"
TARGET_COLUMN = "actual_travel_time_minutes"
UtcTimestamp = Annotated[AwareDatetime, AfterValidator(lambda value: value.astimezone(UTC))]


class SegmentSample(SegmentFeatures):
    sample_schema_version: Literal["segment-sample-v1"] = SAMPLE_SCHEMA_VERSION
    generator_version: Literal["synthetic-segments-v1"] = GENERATOR_VERSION
    data_origin: Literal["synthetic"] = "synthetic"
    scenario_id: Identifier
    traversal_id: Identifier
    segment_id: Identifier
    from_node: Identifier
    to_node: Identifier
    graph_version: Identifier
    timezone: Literal["America/Sao_Paulo"] = "America/Sao_Paulo"
    prediction_at: UtcTimestamp
    planned_departure_at: UtcTimestamp
    context_as_of: UtcTimestamp
    traffic_source: Literal["synthetic-segments-v1"] = GENERATOR_VERSION
    traffic_observed_at: UtcTimestamp
    traffic_available_at: UtcTimestamp
    features_available_at: UtcTimestamp
    entered_at: UtcTimestamp
    exited_at: UtcTimestamp
    recorded_at: UtcTimestamp
    label_available_at: UtcTimestamp
    actual_travel_time_minutes: PositiveNumber

    @model_validator(mode="after")
    def validate_observation(self) -> Self:
        if not (
            self.traffic_observed_at
            <= self.traffic_available_at
            <= self.context_as_of
            <= self.prediction_at
            <= self.planned_departure_at
            <= self.entered_at
            < self.exited_at
            <= self.recorded_at
            <= self.label_available_at
        ):
            raise ValueError("Observation timestamps must follow availability and event order.")
        if not self.traffic_available_at <= self.features_available_at <= self.prediction_at:
            raise ValueError("Features must be available when the prediction is planned.")
        if (self.hour, self.day_of_week) != departure_context(self.planned_departure_at):
            raise ValueError(
                "Hour and weekday must describe planned departure in the graph timezone."
            )
        elapsed = (self.exited_at - self.entered_at).total_seconds() / 60
        if not math.isclose(self.actual_travel_time_minutes, elapsed, rel_tol=1e-9, abs_tol=1e-9):
            raise ValueError("The target must match the observed segment traversal duration.")
        return self


SAMPLE_COLUMNS = tuple(SegmentSample.model_fields)
