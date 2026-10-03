from collections.abc import Sequence

import numpy as np
from sklearn.base import BaseEstimator, RegressorMixin
from sklearn.compose import ColumnTransformer
from sklearn.dummy import DummyRegressor
from sklearn.ensemble import RandomForestRegressor
from sklearn.linear_model import LinearRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from sklearn.utils.validation import check_is_fitted

from app.ml.features import FEATURE_COLUMNS, SegmentFeatures

NUMERIC_COLUMNS = ("distance_km", "reference_speed_kmh", "hour", "day_of_week")
CATEGORICAL_COLUMNS = ("road_type", "traffic_level")
CANDIDATE_NAMES = ("physical_reference", "dummy_median", "linear_regression", "random_forest")


def feature_matrix(features: Sequence[SegmentFeatures]) -> np.ndarray:
    if any(not isinstance(row, SegmentFeatures) for row in features):
        raise ValueError("Feature matrix requires validated segment features.")
    return np.array(
        [[getattr(row, column) for column in FEATURE_COLUMNS] for row in features], dtype=object
    ).reshape(len(features), len(FEATURE_COLUMNS))


class PhysicalReferenceRegressor(RegressorMixin, BaseEstimator):
    """The graph's distance/speed reference; it does not learn the generator's factors."""

    def fit(self, X, y=None):
        self.n_features_in_ = X.shape[1]
        return self

    def predict(self, X):
        check_is_fitted(self)
        with np.errstate(over="ignore", divide="ignore", invalid="ignore"):
            return (
                np.asarray(X[:, FEATURE_COLUMNS.index("distance_km")], dtype=float)
                / np.asarray(X[:, FEATURE_COLUMNS.index("reference_speed_kmh")], dtype=float)
                * 60
            )


def build_candidates(seed: int = 42) -> dict[str, Pipeline]:
    if isinstance(seed, bool) or not isinstance(seed, int) or not 0 <= seed < 2**32:
        raise ValueError("Model seed must be an integer between 0 and 4294967295.")
    estimators = {
        "dummy_median": DummyRegressor(strategy="median"),
        "linear_regression": LinearRegression(),
        "random_forest": RandomForestRegressor(
            n_estimators=100, max_depth=12, min_samples_leaf=3, random_state=seed, n_jobs=1
        ),
    }
    candidates = {"physical_reference": Pipeline([("regressor", PhysicalReferenceRegressor())])}
    for name, estimator in estimators.items():
        preprocessing = ColumnTransformer(
            [
                (
                    "numeric",
                    StandardScaler(),
                    [FEATURE_COLUMNS.index(column) for column in NUMERIC_COLUMNS],
                ),
                (
                    "categorical",
                    OneHotEncoder(handle_unknown="ignore", sparse_output=False),
                    [FEATURE_COLUMNS.index(column) for column in CATEGORICAL_COLUMNS],
                ),
            ],
            remainder="drop",
        )
        candidates[name] = Pipeline([("features", preprocessing), ("regressor", estimator)])
    return candidates
