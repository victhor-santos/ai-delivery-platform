import math

import numpy as np
import pytest

from training.metrics import regression_metrics


def test_metrics_match_hand_calculation_and_preserve_negative_r2():
    report = regression_metrics(np.array([1, 2, 3]), np.array([2, 2, 5]))
    assert report["mae_minutes"] == 1
    assert report["rmse_minutes"] == pytest.approx(math.sqrt(5 / 3))
    assert report["r2"] == pytest.approx(-1.5)
    assert report["invalid_predictions"] == 0


def test_negative_predictions_are_counted_and_scored_without_clamping():
    report = regression_metrics(np.array([1, 2]), np.array([-2, 0]))
    assert report["nonpositive_predictions"] == 2
    assert report["mae_minutes"] == 2.5


def test_nonfinite_predictions_do_not_disappear_from_metrics():
    report = regression_metrics(np.array([1, 2, 3]), np.array([1, np.nan, np.inf]))
    assert report["rows"] == 3
    assert report["invalid_predictions"] == 2
    assert report["mae_minutes"] is None
    assert report["rmse_minutes"] is None


@pytest.mark.parametrize("actual", [[1], [2, 2]])
def test_undefined_r2_is_explicit_without_warnings(actual):
    report = regression_metrics(np.array(actual), np.ones(len(actual)))
    assert report["r2"] is None


@pytest.mark.parametrize("actual, predicted", [([], []), ([1, 2], [1]), ([0], [1])])
def test_invalid_metric_input_is_rejected(actual, predicted):
    with pytest.raises(ValueError):
        regression_metrics(np.array(actual), np.array(predicted))
