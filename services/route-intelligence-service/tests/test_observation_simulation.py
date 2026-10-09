from collections import Counter

import pytest

from app.ml.artifacts import load_model
from app.ml.features import FEATURE_COLUMNS, TRAFFIC_LEVELS, SegmentFeatures
from app.ml.predictor import SegmentTravelTimePredictor
from app.routing.demo import load_demo_graph
from training.observation_dataset import split_observations
from training.observations import load_observations
from training.simulate_observations import (
    SimulationConfig,
    main,
    simulate_observations,
    write_observations,
)

SMALL = {"train_days": 7, "validation_days": 7, "test_days": 7, "deliveries_per_day": 48}


@pytest.fixture(scope="module")
def predictor(model_bundle):
    return SegmentTravelTimePredictor(*load_model(model_bundle[0]))


@pytest.fixture(scope="module")
def rows(predictor):
    return simulate_observations(load_demo_graph(), predictor, SimulationConfig(**SMALL))


def test_simulation_covers_segments_hours_weekdays_and_traffic(rows):
    graph = load_demo_graph()

    assert {row.segment_id for row in rows} == {segment.segment_id for segment in graph.segments}
    assert {row.hour for row in rows} == set(range(24))
    assert {row.day_of_week for row in rows} == set(range(7))
    assert set(Counter(row.traffic_level for row in rows)) == set(TRAFFIC_LEVELS)


def test_stored_predictions_come_from_the_production_model(rows, predictor):
    assert {row.model_version for row in rows} == {predictor.metadata.model_version}
    assert {row.prediction_data_origin for row in rows} == {"synthetic"}
    sample = rows[:20]
    features = [
        SegmentFeatures(**{name: getattr(row, name) for name in FEATURE_COLUMNS}) for row in sample
    ]
    assert [row.predicted_travel_time_minutes for row in sample] == list(
        predictor.predict(features)
    )


def test_simulation_is_reproducible_by_seed(predictor, rows):
    graph = load_demo_graph()

    assert simulate_observations(graph, predictor, SimulationConfig(**SMALL)) == rows
    other = simulate_observations(graph, predictor, SimulationConfig(**SMALL, seed=7))
    assert other != rows


def test_heavy_traffic_shift_changes_only_heavy_traffic_durations(predictor, rows):
    shifted = simulate_observations(
        load_demo_graph(), predictor, SimulationConfig(**SMALL, high_traffic_shift=1.5)
    )

    # Later segments start later after a slower one and may fall in another time period.
    pairs = [pair for pair in zip(rows, shifted, strict=True) if pair[0].sequence == 0]
    assert pairs
    for base, changed in pairs:
        ratio = changed.actual_travel_time_minutes / base.actual_travel_time_minutes
        expected = 1.5 if base.traffic_level == "high" else 1.0
        assert ratio == pytest.approx(expected, rel=1e-6)


def test_export_roundtrips_and_fills_every_partition(rows, tmp_path):
    export = tmp_path / "observations.csv"
    write_observations(rows, export)

    loaded = load_observations([export])
    split = split_observations(loaded, SimulationConfig(**SMALL).split_plan)

    assert loaded.rows == tuple(sorted(rows, key=lambda row: (str(row.delivery_id), row.sequence)))
    assert split.train and split.validation and split.test


def test_simulation_rejects_a_model_trained_on_another_graph(predictor, graph_data):
    from app.routing.graph import RoadGraph

    with pytest.raises(ValueError, match="incompatible"):
        simulate_observations(
            RoadGraph.model_validate(graph_data), predictor, SimulationConfig(**SMALL)
        )


def test_cli_preserves_existing_output(model_bundle, tmp_path, capsys):
    output = tmp_path / "existing.csv"
    output.write_text("keep", encoding="utf-8")

    with pytest.raises(SystemExit) as exit_info:
        main(
            [
                "--graph",
                "app/routing/data/synthetic-city-v1.json",
                "--production-model",
                str(model_bundle[0]),
                "--output",
                str(output),
            ]
        )

    assert exit_info.value.code == 2
    assert output.read_text(encoding="utf-8") == "keep"
    assert "already exists" in capsys.readouterr().err
