from collections import defaultdict
from datetime import UTC, datetime
from statistics import mean

import pytest
from pydantic import ValidationError

from app.ml.features import FEATURE_COLUMNS, extract_features
from app.routing.demo import load_demo_graph
from app.routing.graph import RoadGraph
from training.schema import TARGET_COLUMN
from training.synthetic import GeneratorConfig, generate_samples, time_factor


def small_config(**changes):
    return GeneratorConfig(train_days=1, validation_days=1, test_days=1, **changes)


def test_seed_and_config_reproduce_complete_samples():
    graph = load_demo_graph()
    config = small_config()

    first = generate_samples(graph, config)
    second = generate_samples(graph, config)

    assert first == second
    assert len(first) == 720
    assert len({sample.traversal_id for sample in first}) == len(first)
    assert len({sample.scenario_id for sample in first}) == 72


def test_different_seed_changes_observations_but_preserves_schedule():
    graph = load_demo_graph()
    first = generate_samples(graph, small_config(seed=42))
    second = generate_samples(graph, small_config(seed=43))

    assert first[0].prediction_at == second[0].prediction_at
    assert first[0].traversal_id == second[0].traversal_id
    assert [s.actual_travel_time_minutes for s in first] != [
        s.actual_travel_time_minutes for s in second
    ]


def test_graph_loading_order_does_not_change_generated_samples():
    graph = load_demo_graph()
    data = graph.model_dump()
    data["nodes"] = tuple(reversed(data["nodes"]))
    data["segments"] = tuple(reversed(data["segments"]))

    assert generate_samples(graph, small_config()) == generate_samples(
        RoadGraph.model_validate(data), small_config()
    )


def test_generator_does_not_change_global_random_state():
    import random

    before = random.getstate()
    generate_samples(load_demo_graph(), small_config())

    assert random.getstate() == before


def test_defaults_use_explicit_start_and_42_day_period():
    config = GeneratorConfig()

    assert config.start_at == datetime(2026, 8, 3, 3, tzinfo=UTC)
    assert config.scenario_count == 1008


@pytest.mark.parametrize(
    "changes",
    [
        {"seed": -1},
        {"seed": True},
        {"train_days": 0},
        {"test_days": 366},
        {"train_days": 365},
        {"interval_minutes": 17},
        {"interval_minutes": 0},
        {"noise_min": 1.2, "noise_max": 1.1},
        {"noise_min": 0},
        {"noise_max": float("nan")},
        {"recording_delay_seconds": -1},
        {"start_at": "2026-08-03T00:00:00"},
        {"start_at": "9999-12-31T00:00:00Z"},
    ],
)
def test_invalid_configuration_is_rejected(changes):
    with pytest.raises(ValidationError):
        GeneratorConfig.model_validate(changes)


def test_empty_segment_graph_and_oversized_dataset_are_rejected():
    graph = load_demo_graph()
    empty = RoadGraph.model_validate(dict(graph.model_dump(), segments=[]))

    with pytest.raises(ValueError, match="samples"):
        generate_samples(empty, small_config())
    with pytest.raises(ValueError, match="samples"):
        generate_samples(graph, GeneratorConfig(train_days=300, interval_minutes=15))


@pytest.mark.parametrize("start", ["0001-01-01T00:00:00Z", "0001-01-01T00:00:00+03:00"])
def test_start_must_allow_utc_conversion_and_historical_context(start):
    with pytest.raises(ValidationError):
        GeneratorConfig(start_at=start)


def test_samples_preserve_static_attributes_available_context_and_duration():
    graph = load_demo_graph()
    segments = graph.segments_by_id
    samples = generate_samples(graph, small_config())
    for sample in samples:
        segment = segments[sample.segment_id]
        assert sample.distance_km == segment.distance_km
        assert sample.reference_speed_kmh == segment.reference_speed_kmh
        assert (sample.from_node, sample.to_node) == (segment.from_node, segment.to_node)
        assert sample.features_available_at <= sample.prediction_at < sample.planned_departure_at
        assert sample.actual_travel_time_minutes > 0
        assert (
            sample.actual_travel_time_minutes
            == (sample.exited_at - sample.entered_at).total_seconds() / 60
        )
        assert tuple(extract_features(sample.model_dump()).model_dump()) == FEATURE_COLUMNS
        assert TARGET_COLUMN not in FEATURE_COLUMNS


def test_scenario_context_covers_every_segment_with_segment_specific_traffic():
    samples = generate_samples(load_demo_graph(), small_config())
    scenario = [sample for sample in samples if sample.scenario_id == "scenario-000000"]

    assert len(scenario) == 10
    assert len({sample.planned_departure_at for sample in scenario}) == 1
    assert len({sample.traffic_level for sample in scenario}) > 1


def test_traffic_and_time_profiles_have_plausible_aggregate_trends():
    samples = generate_samples(load_demo_graph(), small_config())
    ratios = defaultdict(list)
    for sample in samples:
        base = sample.distance_km / sample.reference_speed_kmh * 60
        ratio = (
            sample.actual_travel_time_minutes / base / time_factor(sample.hour, sample.day_of_week)
        )
        ratios[sample.traffic_level].append(ratio)

    assert mean(ratios["low"]) < mean(ratios["medium"]) < mean(ratios["high"])
    assert time_factor(19, 0) > time_factor(12, 0) > time_factor(2, 0)
    assert time_factor(19, 6) < time_factor(19, 0)


def test_distance_and_reference_speed_control_duration_when_context_is_fixed(graph_data):
    graph_data["segments"][1].update(road_type="residential", reference_speed_kmh=15)
    graph = RoadGraph.model_validate(graph_data)
    samples = generate_samples(graph, small_config(noise_min=1.0, noise_max=1.0))
    by_context = defaultdict(list)
    for sample in samples:
        by_context[(sample.hour, sample.day_of_week, sample.traffic_level)].append(sample)

    compared = 0
    for group in by_context.values():
        short = [s for s in group if s.segment_id == "A-B"]
        long = [s for s in group if s.segment_id == "B-C"]
        if short and long:
            assert long[0].actual_travel_time_minutes > short[0].actual_travel_time_minutes
            compared += 1
    assert compared > 0
