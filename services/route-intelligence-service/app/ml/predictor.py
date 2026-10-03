from collections.abc import Mapping, Sequence
from pathlib import Path

import numpy as np
from sklearn.pipeline import Pipeline

from app.ml.artifacts import ModelMetadata, load_model
from app.ml.features import SegmentFeatures
from app.ml.pipelines import feature_matrix


class SegmentTravelTimePredictor:
    def __init__(self, model: Pipeline, metadata: ModelMetadata):
        self._model = model
        self.metadata = metadata

    @classmethod
    def from_directory(cls, directory: Path) -> "SegmentTravelTimePredictor":
        model, metadata = load_model(directory)
        return cls(model, metadata)

    def predict(
        self, records: Sequence[SegmentFeatures | Mapping[str, object]]
    ) -> tuple[float, ...]:
        if not 1 <= len(records) <= 1000:
            raise ValueError("Prediction batches must contain between 1 and 1000 segments.")
        features = [
            SegmentFeatures.model_validate(
                record.model_dump() if isinstance(record, SegmentFeatures) else record
            )
            for record in records
        ]
        try:
            with np.errstate(over="raise", invalid="raise", divide="raise"):
                predictions = np.asarray(self._model.predict(feature_matrix(features)), dtype=float)
        except (FloatingPointError, OverflowError) as exc:
            raise ValueError("Prediction exceeded the supported numeric range.") from exc
        if predictions.shape != (len(features),) or not np.all(
            np.isfinite(predictions) & (predictions > 0)
        ):
            raise ValueError("Model must return one positive finite time per segment.")
        return tuple(float(value) for value in predictions)
