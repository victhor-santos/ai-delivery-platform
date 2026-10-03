from collections.abc import Sequence
from dataclasses import dataclass
from statistics import median
from time import perf_counter

import numpy as np
from sklearn.pipeline import Pipeline

from app.ml.pipelines import CANDIDATE_NAMES, build_candidates, feature_matrix
from training.metrics import evaluate_predictions
from training.schema import SegmentSample

MAE_SIMPLICITY_MARGIN_MINUTES = 0.01


def batch_latency(model: Pipeline, matrix: np.ndarray) -> dict:
    batch = matrix[:256]
    model.predict(batch)
    timings = []
    for _ in range(5):
        started = perf_counter()
        model.predict(batch)
        timings.append((perf_counter() - started) * 1000)
    return {"batch_rows": len(batch), "repetitions": 5, "median_ms": median(timings)}


def select_candidate(reports: dict[str, dict]) -> str:
    eligible = {
        name: report["validation"]["overall"]["mae_minutes"]
        for name, report in reports.items()
        if report["train"]["overall"]["invalid_predictions"] == 0
        and report["validation"]["overall"]["invalid_predictions"] == 0
        and report["validation"]["overall"]["mae_minutes"] is not None
    }
    if not eligible:
        raise ValueError("No candidate produced valid predictions on training and validation.")
    best = min(eligible.values())
    return next(
        name
        for name in CANDIDATE_NAMES
        if name in eligible and eligible[name] <= best + MAE_SIMPLICITY_MARGIN_MINUTES
    )


@dataclass(frozen=True)
class TrainingResult:
    model: Pipeline
    selected_name: str
    report: dict


def train_candidates(
    train: Sequence[SegmentSample], validation: Sequence[SegmentSample], seed: int = 42
) -> TrainingResult:
    """Fit on training only, then select on validation. This function receives no test data."""
    if not train or not validation:
        raise ValueError("Training and validation must contain observations.")
    matrices = {"train": feature_matrix(train), "validation": feature_matrix(validation)}
    target = np.array([sample.actual_travel_time_minutes for sample in train])
    candidates = build_candidates(seed)
    reports = {}
    for name, model in candidates.items():
        started = perf_counter()
        model.fit(matrices["train"], target)
        fit_seconds = perf_counter() - started
        reports[name] = {
            "estimator": type(model.named_steps["regressor"]).__name__,
            "parameters": model.named_steps["regressor"].get_params(deep=False),
            "fit_seconds": fit_seconds,
            "train": evaluate_predictions(train, model.predict(matrices["train"])),
            "validation": evaluate_predictions(validation, model.predict(matrices["validation"])),
            "validation_batch_latency": batch_latency(model, matrices["validation"]),
        }
    selected = select_candidate(reports)
    report = {
        "report_version": "segment-validation-report-v1",
        "seed": seed,
        "selection": {
            "selected": selected,
            "partition": "validation",
            "metric": "mae_minutes",
            "requires_valid_train_and_validation_predictions": True,
            "simplicity_order": list(CANDIDATE_NAMES),
            "mae_simplicity_margin_minutes": MAE_SIMPLICITY_MARGIN_MINUTES,
            "refit_on_validation": False,
            "test_used_for_selection": False,
        },
        "candidates": reports,
    }
    return TrainingResult(candidates[selected], selected, report)
