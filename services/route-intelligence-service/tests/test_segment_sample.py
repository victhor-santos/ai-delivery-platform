from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from training.schema import SegmentSample


@pytest.fixture
def sample_data():
    prediction = datetime(2026, 8, 3, 22, tzinfo=UTC)
    return {
        "distance_km": 0.9,
        "road_type": "residential",
        "reference_speed_kmh": 15,
        "traffic_level": "medium",
        "hour": 19,
        "day_of_week": 0,
        "scenario_id": "scenario-0",
        "traversal_id": "scenario-0.A-B",
        "segment_id": "A-B",
        "from_node": "A",
        "to_node": "B",
        "graph_version": "synthetic-city-v1",
        "prediction_at": prediction,
        "planned_departure_at": prediction + timedelta(minutes=1),
        "context_as_of": prediction,
        "traffic_observed_at": prediction - timedelta(minutes=1),
        "traffic_available_at": prediction - timedelta(seconds=30),
        "features_available_at": prediction - timedelta(seconds=30),
        "entered_at": prediction + timedelta(minutes=1),
        "exited_at": prediction + timedelta(minutes=6),
        "recorded_at": prediction + timedelta(minutes=7),
        "label_available_at": prediction + timedelta(minutes=8),
        "actual_travel_time_minutes": 5,
    }


def test_sample_preserves_provenance_and_normalizes_timestamps_to_utc(sample_data):
    sample_data["prediction_at"] = datetime.fromisoformat("2026-08-03T19:00:00-03:00")

    sample = SegmentSample.model_validate(sample_data)

    assert sample.data_origin == "synthetic"
    assert sample.prediction_at == datetime(2026, 8, 3, 22, tzinfo=UTC)
    assert sample.prediction_at.tzinfo == UTC


@pytest.mark.parametrize(
    "field,delta",
    [
        ("traffic_observed_at", 1),
        ("traffic_available_at", 1),
        ("context_as_of", 1),
        ("features_available_at", 1),
        ("planned_departure_at", -1),
        ("entered_at", -1),
        ("exited_at", -1),
        ("recorded_at", -1),
        ("label_available_at", -1),
    ],
)
def test_future_information_and_incoherent_observations_are_rejected(sample_data, field, delta):
    sample_data[field] = sample_data["prediction_at"] + timedelta(days=delta)

    with pytest.raises(ValidationError):
        SegmentSample.model_validate(sample_data)


@pytest.mark.parametrize("field", ["hour", "day_of_week", "actual_travel_time_minutes"])
def test_feature_context_and_target_must_match_the_observation(sample_data, field):
    sample_data[field] = 1

    with pytest.raises(ValidationError):
        SegmentSample.model_validate(sample_data)


def test_naive_observation_timestamp_is_rejected(sample_data):
    sample_data["prediction_at"] = datetime(2026, 8, 3, 22)

    with pytest.raises(ValidationError):
        SegmentSample.model_validate(sample_data)
