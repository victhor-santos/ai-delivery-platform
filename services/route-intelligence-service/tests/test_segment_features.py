from datetime import datetime

import pytest
from pydantic import ValidationError

from app.ml.features import (
    FEATURE_COLUMNS,
    SegmentFeatures,
    departure_context,
    extract_features,
    features_for_segment,
)
from app.routing.demo import load_demo_graph


@pytest.fixture
def feature_data():
    return {
        "distance_km": 0.9,
        "road_type": "residential",
        "reference_speed_kmh": 15,
        "traffic_level": "medium",
        "hour": 19,
        "day_of_week": 0,
    }


def test_extraction_allows_only_features_and_excludes_outcomes_and_identity(feature_data):
    record = dict(
        feature_data,
        actual_travel_time_minutes=999,
        scenario_id="test",
        segment_id="A-B",
        exited_at="future",
        label_available_at="future",
        noise_factor=1.1,
    )

    features = extract_features(record)

    assert tuple(features.model_dump()) == FEATURE_COLUMNS
    assert features.model_dump() == feature_data


def test_direct_features_reject_extra_columns(feature_data):
    with pytest.raises(ValidationError):
        SegmentFeatures.model_validate(dict(feature_data, actual_travel_time_minutes=999))


@pytest.mark.parametrize("column", FEATURE_COLUMNS)
def test_missing_features_are_rejected(feature_data, column):
    del feature_data[column]

    with pytest.raises(ValidationError):
        extract_features(feature_data)


@pytest.mark.parametrize(
    "field,value",
    [
        ("distance_km", 0),
        ("distance_km", float("nan")),
        ("reference_speed_kmh", -1),
        ("road_type", "footpath"),
        ("traffic_level", "unknown"),
        ("hour", -1),
        ("hour", 24),
        ("hour", "19"),
        ("hour", True),
        ("day_of_week", 7),
        ("day_of_week", 1.5),
    ],
)
def test_invalid_feature_values_are_rejected(feature_data, field, value):
    feature_data[field] = value

    with pytest.raises(ValidationError):
        SegmentFeatures.model_validate(feature_data)


@pytest.mark.parametrize(
    "departure,expected",
    [
        ("2026-08-03T02:30:00+00:00", (23, 6)),
        ("2026-08-03T03:00:00+00:00", (0, 0)),
        ("2026-08-03T19:00:00-03:00", (19, 0)),
    ],
)
def test_departure_context_uses_graph_timezone_and_monday_zero(departure, expected):
    assert departure_context(datetime.fromisoformat(departure)) == expected


def test_naive_departure_is_rejected():
    with pytest.raises(ValueError, match="explicit timezone"):
        departure_context(datetime(2026, 8, 3))


def test_segment_and_departure_produce_the_same_shared_features():
    segment = load_demo_graph().segments_by_id["A-B"]

    features = features_for_segment(segment, "high", datetime.fromisoformat("2026-08-03T22:00:00Z"))

    assert features.model_dump() == {
        "distance_km": 0.9,
        "road_type": "residential",
        "reference_speed_kmh": 15.0,
        "traffic_level": "high",
        "hour": 19,
        "day_of_week": 0,
    }
