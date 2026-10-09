import hashlib
import json
import shutil
from unittest.mock import patch

import pytest

from app.ml.artifacts import (
    MODEL_FILE,
    ModelMetadata,
    ObservationModelMetadata,
    load_model,
    load_model_bundle,
    load_observation_model,
)
from app.ml.predictor import SegmentTravelTimePredictor
from training.serialization import json_bytes


@pytest.fixture
def observation_bundle(model_bundle, tmp_path):
    original, _ = model_bundle
    output = tmp_path / "observation-artifact"
    shutil.copytree(original, output)
    metadata = json.loads((output / "metadata.json").read_bytes())
    report = json.loads((output / "validation-report.json").read_bytes())
    report.update(
        report_version=ObservationModelMetadata.VALIDATION_REPORT_VERSION,
        data_origin="simulated",
        prediction_data_origin="synthetic",
        sample_schema_version="delivery-segment-observation-v1",
        dataset_manifest_version="delivery-observation-dataset-v1",
        graph_version=metadata["graph_version"],
        graph_sha256=metadata["graph_sha256"],
    )
    content = json_bytes(report)
    (output / "validation-report.json").write_bytes(content)
    metadata.update(
        artifact_schema_version="delivery-observation-model-artifact-v1",
        model_version=(
            f"{ObservationModelMetadata.MODEL_VERSION_PREFIX}-{metadata['artifact_sha256'][:16]}"
        ),
        sample_schema_version="delivery-segment-observation-v1",
        dataset_manifest_version="delivery-observation-dataset-v1",
        data_origin="simulated",
        prediction_data_origin="synthetic",
        validation_report_sha256=hashlib.sha256(content).hexdigest(),
    )
    metadata = ObservationModelMetadata.model_validate(metadata)
    (output / "metadata.json").write_bytes(json_bytes(metadata.model_dump(mode="json")))
    return output


def rewrite_metadata(directory, **changes):
    metadata = json.loads((directory / "metadata.json").read_bytes())
    metadata.update(changes)
    (directory / "metadata.json").write_bytes(json_bytes(metadata))


def rewrite_report(directory, mutate):
    report = json.loads((directory / "validation-report.json").read_bytes())
    mutate(report)
    content = json_bytes(report)
    (directory / "validation-report.json").write_bytes(content)
    rewrite_metadata(directory, validation_report_sha256=hashlib.sha256(content).hexdigest())


def test_observation_loader_reuses_pipeline_with_separate_provenance(
    model_bundle, observation_bundle
):
    original, _ = model_bundle
    model, metadata = load_observation_model(observation_bundle)
    original_model, original_metadata = load_model(original)
    assert type(model) is type(original_model)
    assert model.n_features_in_ == original_model.n_features_in_
    assert metadata.artifact_sha256 == original_metadata.artifact_sha256
    assert metadata.model_version != original_metadata.model_version
    assert metadata.model_version.startswith("segment-observation-model-v1-")
    assert metadata.data_origin == "simulated"
    assert metadata.prediction_data_origin == "synthetic"
    assert metadata.fit_partition == "train"
    assert metadata.selection_partition == "validation"
    assert (observation_bundle / MODEL_FILE).read_bytes() == (original / MODEL_FILE).read_bytes()
    assert load_model_bundle(observation_bundle, ObservationModelMetadata)[1] == metadata


def test_model_metadata_v1_json_is_unchanged_and_class_variables_are_not_fields(
    model_bundle, observation_bundle
):
    original, _ = model_bundle
    raw = json.loads((original / "metadata.json").read_bytes())
    assert ModelMetadata.model_validate(raw).model_dump(mode="json") == raw
    observation = load_observation_model(observation_bundle)[1].model_dump(mode="json")
    assert set(observation) - set(raw) == {"dataset_manifest_version", "prediction_data_origin"}
    for payload in (raw, observation):
        assert "MODEL_VERSION_PREFIX" not in payload
        assert "VALIDATION_REPORT_VERSION" not in payload


@pytest.mark.parametrize(
    "online_entry_point", [load_model, SegmentTravelTimePredictor.from_directory]
)
def test_runtime_entry_points_reject_unpromoted_observation_bundle_before_deserialization(
    observation_bundle, online_entry_point
):
    with patch("app.ml.artifacts.joblib.load") as deserialize:
        with pytest.raises(ValueError, match="approved promotion"):
            online_entry_point(observation_bundle)
        deserialize.assert_not_called()


def test_observation_loader_rejects_synthetic_contract_before_deserialization(model_bundle):
    original, _ = model_bundle
    with patch("app.ml.artifacts.joblib.load") as deserialize:
        with pytest.raises(ValueError, match="artifact_schema_version"):
            load_observation_model(original)
        deserialize.assert_not_called()


@pytest.mark.parametrize(
    "changes, message",
    [
        ({"model_version": "segment-model-v1-0123456789abcdef"}, "model_version"),
        ({"model_version": "segment-observation-model-v1-0000000000000000"}, "checksum"),
        ({"data_origin": "synthetic"}, "data_origin"),
        ({"prediction_data_origin": "simulated"}, "prediction_data_origin"),
        ({"sample_schema_version": "segment-sample-v1"}, "sample_schema_version"),
        ({"dataset_manifest_version": "segment-dataset-manifest-v1"}, "dataset_manifest_version"),
        ({"seed": -1}, "seed"),
        ({"seed": 2**32}, "seed"),
        ({"seed": True}, "seed"),
        ({"seed": "42"}, "seed"),
    ],
)
def test_observation_metadata_incompatibility_is_rejected_before_deserialization(
    observation_bundle, changes, message
):
    rewrite_metadata(observation_bundle, **changes)
    with patch("app.ml.artifacts.joblib.load") as deserialize:
        with pytest.raises(ValueError, match=message):
            load_observation_model(observation_bundle)
        deserialize.assert_not_called()


def test_observation_bundle_checks_runtime_versions_before_deserialization(observation_bundle):
    metadata = json.loads((observation_bundle / "metadata.json").read_bytes())
    metadata["environment"]["scikit-learn"] = "0.0.0"
    rewrite_metadata(observation_bundle, environment=metadata["environment"])
    with patch("app.ml.artifacts.joblib.load") as deserialize:
        with pytest.raises(ValueError, match="versions"):
            load_observation_model(observation_bundle)
        deserialize.assert_not_called()


@pytest.mark.parametrize(
    "mutate",
    [
        lambda report: report.update(report_version="segment-validation-report-v1"),
        lambda report: report.update(data_origin="synthetic"),
        lambda report: report.update(prediction_data_origin="simulated"),
        lambda report: report.update(sample_schema_version="segment-sample-v1"),
        lambda report: report.update(dataset_manifest_version="segment-dataset-manifest-v1"),
        lambda report: report.update(dataset_id="a" * 64),
        lambda report: report.update(dataset_manifest_sha256="a" * 64),
        lambda report: report.update(graph_version="incompatible-graph-v1"),
        lambda report: report.update(graph_sha256="a" * 64),
        lambda report: report.update(seed=43),
        lambda report: report.update(seed=42.0),
        lambda report: report["selection"].update(test_used_for_selection=True),
        lambda report: report["selection"].update(refit_on_validation=True),
    ],
)
def test_observation_report_provenance_rejected_even_with_matching_checksum(
    observation_bundle, mutate
):
    rewrite_report(observation_bundle, mutate)
    with pytest.raises(ValueError, match="provenance"):
        load_observation_model(observation_bundle)


def test_report_seed_cannot_use_boolean_equality_with_integer_metadata(observation_bundle):
    rewrite_metadata(observation_bundle, seed=1)
    rewrite_report(observation_bundle, lambda report: report.update(seed=True))
    with pytest.raises(ValueError, match="provenance"):
        load_observation_model(observation_bundle)


@pytest.mark.parametrize(
    "changes",
    [
        {"report_version": "delivery-observation-validation-report-v1"},
        {"data_origin": "simulated"},
    ],
)
def test_synthetic_report_version_and_origin_remain_bound_to_metadata(
    model_bundle, tmp_path, changes
):
    original, _ = model_bundle
    output = tmp_path / "synthetic-modified"
    shutil.copytree(original, output)
    rewrite_report(output, lambda report: report.update(changes))
    with pytest.raises(ValueError, match="provenance"):
        load_model(output)


@pytest.mark.parametrize("name", [MODEL_FILE, "validation-report.json"])
def test_observation_bundle_corruption_is_rejected_before_deserialization(observation_bundle, name):
    path = observation_bundle / name
    content = bytearray(path.read_bytes())
    content[0] ^= 255
    path.write_bytes(content)
    with patch("app.ml.artifacts.joblib.load") as deserialize:
        with pytest.raises(ValueError, match="checksum"):
            load_observation_model(observation_bundle)
        deserialize.assert_not_called()


def test_missing_observation_artifact_has_controlled_error(tmp_path):
    with pytest.raises(ValueError, match="Unable to load model artifact"):
        load_observation_model(tmp_path / "missing")
