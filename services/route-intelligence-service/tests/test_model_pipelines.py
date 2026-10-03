import numpy as np
import pytest

from app.ml.features import FEATURE_COLUMNS, SegmentFeatures
from app.ml.pipelines import build_candidates, feature_matrix


def features(distance=1, road="residential", traffic="low"):
    return SegmentFeatures(
        distance_km=distance,
        reference_speed_kmh=30,
        road_type=road,
        traffic_level=traffic,
        hour=9,
        day_of_week=0,
    )


def test_matrix_contains_only_ordered_allowed_features():
    matrix = feature_matrix([features()])
    assert matrix.shape == (1, len(FEATURE_COLUMNS))
    assert matrix.tolist() == [[1.0, "residential", 30.0, "low", 9, 0]]
    assert feature_matrix([]).shape == (0, 6)
    with pytest.raises(ValueError, match="validated"):
        feature_matrix([{"distance_km": 1, "actual_travel_time_minutes": 2}])


def test_preprocessing_learns_only_training_values_and_handles_unseen_valid_categories():
    train = feature_matrix([features(1), features(3)])
    validation = feature_matrix([features(100, road="highway", traffic="high")])
    model = build_candidates()["linear_regression"].fit(train, [2.0, 6.0])
    preprocessing = model.named_steps["features"]
    assert preprocessing.named_transformers_["numeric"].mean_[0] == 2.0
    encoder = preprocessing.named_transformers_["categorical"]
    assert encoder.categories_[0].tolist() == ["residential"]
    assert encoder.categories_[1].tolist() == ["low"]
    assert np.isfinite(model.predict(validation)).all()
    assert preprocessing.named_transformers_["numeric"].mean_[0] == 2.0


def test_dummy_is_median_and_physical_reference_does_not_learn_targets():
    matrix = feature_matrix([features(1), features(2), features(3)])
    candidates = build_candidates()
    assert candidates["dummy_median"].fit(matrix, [5, 9, 100]).predict(matrix).tolist() == [9] * 3
    assert candidates["physical_reference"].fit(matrix, [5, 9, 100]).predict(matrix).tolist() == [
        2,
        4,
        6,
    ]


def test_forest_is_reproducible_with_bounded_complexity():
    matrix = feature_matrix([features(i) for i in range(1, 20)])
    target = np.arange(1, 20) * 2
    first = build_candidates(123)["random_forest"].fit(matrix, target)
    second = build_candidates(123)["random_forest"].fit(matrix, target)
    np.testing.assert_array_equal(first.predict(matrix), second.predict(matrix))
    estimator = first.named_steps["regressor"]
    assert estimator.n_jobs == 1
    assert estimator.max_depth == 12


@pytest.mark.parametrize("seed", [True, -1, 2**32, "42"])
def test_invalid_seed_is_rejected(seed):
    with pytest.raises(ValueError, match="seed"):
        build_candidates(seed)
