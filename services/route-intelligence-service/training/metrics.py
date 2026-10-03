from collections.abc import Sequence

import numpy as np
from sklearn.metrics import mean_absolute_error, r2_score, root_mean_squared_error

from training.schema import SegmentSample


def regression_metrics(actual: np.ndarray, predicted: np.ndarray) -> dict:
    actual = np.asarray(actual, dtype=float)
    predicted = np.asarray(predicted, dtype=float)
    if actual.ndim != 1 or not len(actual) or predicted.shape != actual.shape:
        raise ValueError("Metrics require matching, nonempty one-dimensional arrays.")
    if not np.all(np.isfinite(actual)) or np.any(actual <= 0):
        raise ValueError("Observed times must be finite and positive.")
    nonfinite = int(np.count_nonzero(~np.isfinite(predicted)))
    nonpositive = int(np.count_nonzero(np.isfinite(predicted) & (predicted <= 0)))
    values = {"mae_minutes": None, "rmse_minutes": None, "r2": None}
    if not nonfinite:
        try:
            with np.errstate(over="raise", invalid="raise", divide="raise"):
                values = {
                    "mae_minutes": float(mean_absolute_error(actual, predicted)),
                    "rmse_minutes": float(root_mean_squared_error(actual, predicted)),
                    "r2": (
                        float(r2_score(actual, predicted, force_finite=False))
                        if len(actual) > 1 and np.ptp(actual) > 0
                        else None
                    ),
                }
        except FloatingPointError as exc:
            raise ValueError("Metric calculation exceeded the supported numeric range.") from exc
    return {
        "rows": len(actual),
        "invalid_predictions": nonfinite + nonpositive,
        "nonfinite_predictions": nonfinite,
        "nonpositive_predictions": nonpositive,
        **values,
    }


def distance_band(distance_km: float) -> str:
    if distance_km < 1:
        return "under_1_km"
    if distance_km < 3:
        return "1_to_under_3_km"
    return "3_km_or_more"


def evaluate_predictions(samples: Sequence[SegmentSample], predictions: np.ndarray) -> dict:
    actual = np.array([sample.actual_travel_time_minutes for sample in samples])
    overall = regression_metrics(actual, predictions)
    grouped = {}
    for dimension in ("road_type", "traffic_level", "distance_band"):
        labels = [
            distance_band(sample.distance_km)
            if dimension == "distance_band"
            else getattr(sample, dimension)
            for sample in samples
        ]
        grouped[dimension] = {}
        for label in sorted(set(labels)):
            mask = np.array([value == label for value in labels])
            grouped[dimension][label] = regression_metrics(actual[mask], predictions[mask])
    return {"overall": overall, "by_group": grouped}
