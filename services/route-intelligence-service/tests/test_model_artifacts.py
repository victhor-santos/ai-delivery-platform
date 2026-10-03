import hashlib
import json
import shutil
from unittest.mock import patch

import numpy as np
import pytest

from app.ml.artifacts import MODEL_FILE, load_model
from app.ml.features import extract_features
from app.ml.pipelines import feature_matrix
from app.ml.predictor import SegmentTravelTimePredictor
from training.load_dataset import load_dataset
from training.serialization import json_bytes
from training.train import main, train_dataset


def test_saved_pipeline_and_predictor_produce_same_predictions(model_bundle):
    artifact, directory = model_bundle
    model, metadata = load_model(artifact)
    predictor = SegmentTravelTimePredictor.from_directory(artifact)
    samples = load_dataset(directory).split.test
    features = [extract_features(s.model_dump()) for s in samples]
    np.testing.assert_allclose(
        predictor.predict(features), model.predict(feature_matrix(features)), rtol=1e-12, atol=1e-12
    )
    assert metadata.selected_candidate == "random_forest"
    assert metadata.fit_partition == "train"
    assert metadata.selection_partition == "validation"


def test_serialized_model_bytes_are_reproducible(model_bundle, tmp_path):
    original, directory = model_bundle
    output = tmp_path / "repeat"
    metadata = train_dataset(load_dataset(directory), output)
    assert (original / MODEL_FILE).read_bytes() == (output / MODEL_FILE).read_bytes()
    assert metadata.model_version == load_model(original)[1].model_version


@pytest.mark.parametrize(
    "mutate, message",
    [
        (lambda m: m["environment"].update({"scikit-learn": "0.0.0"}), "versions"),
        (lambda m: m.update(feature_schema_version="segment-features-v999"), "feature_schema"),
        (lambda m: m.update(feature_columns=list(reversed(m["feature_columns"]))), "feature order"),
    ],
)
def test_incompatible_metadata_is_rejected_before_deserialization(
    model_bundle, tmp_path, mutate, message
):
    artifact, _ = model_bundle
    output = tmp_path / "modified"
    shutil.copytree(artifact, output)
    metadata = json.loads((output / "metadata.json").read_bytes())
    mutate(metadata)
    (output / "metadata.json").write_bytes(json_bytes(metadata))
    with patch("app.ml.artifacts.joblib.load") as deserialize:
        with pytest.raises(ValueError, match=message):
            load_model(output)
        deserialize.assert_not_called()


def test_corrupt_artifact_is_rejected_before_deserialization(model_bundle, tmp_path):
    artifact, _ = model_bundle
    output = tmp_path / "corrupt"
    shutil.copytree(artifact, output)
    content = bytearray((output / MODEL_FILE).read_bytes())
    content[0] ^= 255
    (output / MODEL_FILE).write_bytes(content)
    with patch("app.ml.artifacts.joblib.load") as deserialize:
        with pytest.raises(ValueError, match="checksum"):
            load_model(output)
        deserialize.assert_not_called()


def test_report_from_another_dataset_is_rejected_even_after_checksum_update(model_bundle, tmp_path):
    artifact, _ = model_bundle
    output = tmp_path / "mixed"
    shutil.copytree(artifact, output)
    report = json.loads((output / "validation-report.json").read_bytes())
    report["dataset_id"] = "a" * 64
    content = json_bytes(report)
    (output / "validation-report.json").write_bytes(content)
    metadata = json.loads((output / "metadata.json").read_bytes())
    metadata["validation_report_sha256"] = hashlib.sha256(content).hexdigest()
    (output / "metadata.json").write_bytes(json_bytes(metadata))
    with pytest.raises(ValueError, match="provenance"):
        load_model(output)


def test_missing_artifact_and_existing_output_have_controlled_errors(model_bundle, tmp_path):
    artifact, directory = model_bundle
    original = (artifact / MODEL_FILE).read_bytes()
    with pytest.raises(ValueError, match="Unable to load"):
        load_model(tmp_path / "missing")
    with pytest.raises(ValueError, match="already exists"):
        train_dataset(load_dataset(directory), artifact)
    assert (artifact / MODEL_FILE).read_bytes() == original


def test_predictor_rejects_leaked_target_missing_fields_and_unknown_categories(model_bundle):
    artifact, directory = model_bundle
    predictor = SegmentTravelTimePredictor.from_directory(artifact)
    features = extract_features(load_dataset(directory).split.test[0].model_dump()).model_dump()
    invalid = [
        dict(features, actual_travel_time_minutes=1),
        dict(features, traffic_level="gridlock"),
        {k: v for k, v in features.items() if k != "reference_speed_kmh"},
        dict(features, distance_km=True),
    ]
    for record in invalid:
        with pytest.raises(ValueError):
            predictor.predict([record])
    for batch in ([], [features] * 1001):
        with pytest.raises(ValueError, match="batches"):
            predictor.predict(batch)


@pytest.mark.parametrize("values", [[0], [-1], [np.nan], [np.inf], [1, 2]])
def test_predictor_refuses_invalid_times_without_clamping(model_bundle, values):
    artifact, directory = model_bundle
    model, metadata = load_model(artifact)
    predictor = SegmentTravelTimePredictor(model, metadata)
    features = extract_features(load_dataset(directory).split.test[0].model_dump())
    with patch.object(model, "predict", return_value=np.array(values)):
        with pytest.raises(ValueError, match="positive finite"):
            predictor.predict([features])


def test_training_command_creates_bundle_and_reports_metadata(model_bundle, tmp_path, capsys):
    _, directory = model_bundle
    output = tmp_path / "command"
    main(["--dataset", str(directory), "--output", str(output)])
    summary = json.loads(capsys.readouterr().out)
    assert summary["selected_candidate"] == "random_forest"
    assert load_model(output)[1].model_version == summary["model_version"]


def test_training_command_reports_invalid_dataset_without_creating_output(tmp_path, capsys):
    output = tmp_path / "artifact"
    with pytest.raises(SystemExit) as error:
        main(["--dataset", str(tmp_path / "missing"), "--output", str(output)])
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert not output.exists()
