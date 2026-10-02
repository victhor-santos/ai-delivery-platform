from collections import defaultdict
from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from app.ml.features import departure_context
from app.routing.demo import load_demo_graph
from training.schema import SegmentSample
from training.splits import SplitPlan, split_plan_for, split_samples
from training.synthetic import GeneratorConfig, generate_samples


@pytest.fixture
def plan():
    return SplitPlan(
        start_at="2026-08-03T03:00:00Z",
        train_end="2026-08-04T03:00:00Z",
        validation_end="2026-08-05T03:00:00Z",
        test_end="2026-08-06T03:00:00Z",
    )


def sample_at(prediction, scenario_id, segment_id="A-B", label_delay=0):
    departure = prediction + timedelta(minutes=1)
    hour, weekday = departure_context(departure)
    exit_at = departure + timedelta(minutes=5)
    return SegmentSample(
        distance_km=0.9,
        road_type="residential",
        reference_speed_kmh=15,
        traffic_level="medium",
        hour=hour,
        day_of_week=weekday,
        scenario_id=scenario_id,
        traversal_id=f"{scenario_id}.{segment_id}",
        segment_id=segment_id,
        from_node="A",
        to_node="B",
        graph_version="test-v1",
        prediction_at=prediction,
        planned_departure_at=departure,
        context_as_of=prediction,
        traffic_observed_at=prediction - timedelta(minutes=1),
        traffic_available_at=prediction - timedelta(seconds=30),
        features_available_at=prediction - timedelta(seconds=30),
        entered_at=departure,
        exited_at=exit_at,
        recorded_at=exit_at,
        label_available_at=exit_at + timedelta(seconds=label_delay),
        actual_travel_time_minutes=5,
    )


def test_temporal_split_keeps_scenarios_whole_and_respects_availability():
    config = GeneratorConfig(train_days=1, validation_days=1, test_days=1)
    samples = generate_samples(load_demo_graph(), config)
    result = split_samples(samples, split_plan_for(config))
    groups = []
    for name, cutoff in (
        ("train", result.plan.train_end),
        ("validation", result.plan.validation_end),
        ("test", result.plan.test_end),
    ):
        rows = getattr(result, name)
        assert len(rows) == 240
        groups.append({row.scenario_id for row in rows})
        assert max(row.label_available_at for row in rows) <= cutoff
        assert all(row.features_available_at <= row.prediction_at for row in rows)
        counts = defaultdict(int)
        for row in rows:
            counts[row.scenario_id] += 1
        assert set(counts.values()) == {10}
    assert groups[0].isdisjoint(groups[1] | groups[2])
    assert groups[1].isdisjoint(groups[2])
    assert max(row.label_available_at for row in result.train) <= min(
        row.prediction_at for row in result.validation
    )
    assert max(row.label_available_at for row in result.validation) <= min(
        row.prediction_at for row in result.test
    )
    assert not result.excluded


def test_group_crossing_cutoff_is_fully_excluded(plan):
    first = sample_at(plan.train_end - timedelta(hours=1), "shared", "A-B")
    second = sample_at(plan.train_end + timedelta(hours=1), "shared", "B-C")

    result = split_samples((first, second), plan)

    assert not result.train and not result.validation
    assert result.excluded == (first, second)
    assert result.excluded_reasons == {"shared": "crosses_time_boundary"}


@pytest.mark.parametrize("cutoff_name", ["train_end", "validation_end", "test_end"])
def test_one_late_label_excludes_entire_group(plan, cutoff_name):
    prediction = getattr(plan, cutoff_name) - timedelta(hours=1)
    first = sample_at(prediction, "late", "A-B")
    second = sample_at(prediction, "late", "B-C", label_delay=3600)

    result = split_samples((first, second), plan)

    assert not result.train and not result.validation and not result.test
    assert len(result.excluded) == 2
    assert result.excluded_reasons == {"late": "label_unavailable_at_cutoff"}


def test_prediction_exactly_at_cutoff_belongs_to_next_partition(plan):
    row = sample_at(plan.train_end, "boundary")

    result = split_samples((row,), plan)

    assert result.validation == (row,)
    assert not result.train


def test_label_available_exactly_at_cutoff_is_accepted(plan):
    row = sample_at(plan.train_end - timedelta(minutes=6), "boundary")

    assert split_samples((row,), plan).train == (row,)


def test_partition_uses_decision_time_instead_of_future_planned_departure(plan):
    row = sample_at(plan.train_end - timedelta(seconds=30), "crossing")

    result = split_samples((row,), plan)

    assert not result.validation
    assert result.excluded_reasons == {"crossing": "label_unavailable_at_cutoff"}


@pytest.mark.parametrize("offset", [-1, 3])
def test_samples_outside_period_are_excluded(plan, offset):
    row = sample_at(plan.start_at + timedelta(days=offset), "outside")

    assert split_samples((row,), plan).excluded_reasons == {"outside": "outside_period"}


def test_duplicate_traversal_ids_are_rejected(plan):
    row = sample_at(plan.start_at, "duplicate")

    with pytest.raises(ValueError, match="unique"):
        split_samples((row, row), plan)


def test_empty_dataset_and_invalid_boundaries_are_rejected(plan):
    with pytest.raises(ValueError, match="empty"):
        split_samples((), plan)
    with pytest.raises(ValidationError, match="strictly increasing"):
        SplitPlan.model_validate(dict(plan.model_dump(), train_end=plan.validation_end))


def test_records_with_different_graph_versions_are_rejected(plan):
    first = sample_at(plan.start_at, "first")
    data = sample_at(plan.start_at, "second").model_dump()
    data["graph_version"] = "other-v1"

    with pytest.raises(ValueError, match="one graph version"):
        split_samples((first, SegmentSample.model_validate(data)), plan)


def test_partition_order_is_reproducible_and_input_is_not_modified(plan):
    first = sample_at(plan.start_at, "first")
    second = sample_at(plan.start_at + timedelta(hours=1), "second")

    assert split_samples((first, second), plan) == split_samples((second, first), plan)
    assert first.prediction_at == datetime(2026, 8, 3, 3, tzinfo=UTC)
