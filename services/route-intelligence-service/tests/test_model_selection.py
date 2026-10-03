import numpy as np
import pytest

from app.ml.pipelines import feature_matrix
from app.routing.demo import load_demo_graph
from training.experiment import select_candidate, train_candidates
from training.splits import split_plan_for, split_samples
from training.synthetic import GeneratorConfig, generate_samples


def report(mae, invalid=0):
    return {
        "train": {"overall": {"invalid_predictions": 0}},
        "validation": {"overall": {"invalid_predictions": invalid, "mae_minutes": mae}},
    }


def test_selection_rejects_invalid_predictions_even_when_mae_is_lower():
    assert (
        select_candidate(
            {"linear_regression": report(0.01, invalid=1), "random_forest": report(0.5)}
        )
        == "random_forest"
    )


def test_selection_prefers_simplicity_within_predeclared_margin():
    assert (
        select_candidate({"physical_reference": report(1.005), "random_forest": report(1)})
        == "physical_reference"
    )


def test_selection_reports_when_all_candidates_are_invalid():
    with pytest.raises(ValueError, match="No candidate"):
        select_candidate({"linear_regression": report(None, invalid=1)})


def test_training_and_selection_are_reproducible_without_receiving_test_rows():
    config = GeneratorConfig(train_days=7, validation_days=7, test_days=7, interval_minutes=360)
    samples = generate_samples(load_demo_graph(), config)
    split = split_samples(samples, split_plan_for(config))
    first = train_candidates(split.train, split.validation)
    second = train_candidates(split.train, split.validation)
    assert first.selected_name == second.selected_name == "random_forest"
    matrix = feature_matrix(split.validation)
    np.testing.assert_array_equal(first.model.predict(matrix), second.model.predict(matrix))
    for name in first.report["candidates"]:
        assert (
            first.report["candidates"][name]["validation"]
            == second.report["candidates"][name]["validation"]
        )
    assert first.report["selection"]["test_used_for_selection"] is False
    assert "test" not in first.report["candidates"]["random_forest"]
