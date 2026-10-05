import csv
import json
from dataclasses import replace
from datetime import timedelta

import numpy as np
import pytest

from app.ml.artifacts import ObservationModelMetadata, load_model, load_observation_model
from app.ml.pipelines import CANDIDATE_NAMES, feature_matrix
from training import train_observations
from training.dataset import graph_checksum
from training.observation_dataset import export_observation_dataset, load_observation_dataset
from training.observations import OBSERVATION_COLUMNS, DeliveryObservation, load_observations
from training.train_observations import main, train_observation_dataset


def artifact_contents(directory):
    return {path.name: path.read_bytes() for path in directory.iterdir()}


def test_training_uses_only_train_and_validation_for_candidate_selection(
    observation_training_dataset, tmp_path, monkeypatch
):
    data, _, graph = observation_training_dataset
    original = train_observations.train_candidates
    calls = []

    def capture(train, validation, seed=42):
        calls.append((train, validation, seed))
        return original(train, validation, seed)

    monkeypatch.setattr(train_observations, "train_candidates", capture)
    metadata = train_observation_dataset(data, tmp_path / "artifact", graph, seed=17)
    assert calls == [(data.split.train, data.split.validation, 17)]
    fitted_ids = {row.delivery_id for rows in calls[0][:2] for row in rows}
    assert fitted_ids.isdisjoint(row.delivery_id for row in data.split.test)
    assert metadata.selected_candidate in CANDIDATE_NAMES
    assert metadata.seed == 17


def test_observation_bundle_roundtrip_preserves_origin_and_offline_identity(
    observation_model_bundle,
):
    artifact, data, graph = observation_model_bundle
    before = artifact_contents(artifact)
    model, metadata = load_observation_model(artifact)
    assert isinstance(metadata, ObservationModelMetadata)
    assert metadata.data_origin == "simulated"
    assert metadata.prediction_data_origin == "synthetic"
    assert metadata.dataset_id == data.dataset_id
    assert metadata.dataset_manifest_sha256 == data.manifest_sha256
    assert metadata.graph_version == graph.graph_version
    assert metadata.graph_sha256 == graph_checksum(graph)
    assert metadata.fit_partition == "train"
    assert metadata.selection_partition == "validation"
    predictions = model.predict(feature_matrix(data.split.test))
    assert predictions.shape == (24,)
    assert np.isfinite(predictions).all() and (predictions > 0).all()
    report = json.loads((artifact / "validation-report.json").read_bytes())
    assert report["data_origin"] == "simulated"
    assert report["prediction_data_origin"] == "synthetic"
    assert report["selection"]["test_used_for_selection"] is False
    assert report["selection"]["refit_on_validation"] is False
    assert report["selection"]["partition"] == "validation"
    assert set(report["candidates"]) == set(CANDIDATE_NAMES)
    for candidate in report["candidates"].values():
        assert candidate["train"]["overall"]["rows"] == len(data.split.train)
        assert candidate["validation"]["overall"]["rows"] == len(data.split.validation)
        assert "test" not in candidate
    with pytest.raises(ValueError):
        load_model(artifact)
    assert artifact_contents(artifact) == before


def test_changing_only_test_targets_does_not_change_selected_pipeline(
    observation_training_dataset, observation_model_bundle, tmp_path
):
    data, _, graph = observation_training_dataset
    original_artifact, _, _ = observation_model_bundle
    changed_test = []
    for row in data.split.test:
        actual = row.actual_travel_time_minutes * 2
        exited = row.entered_at + timedelta(minutes=actual)
        changed_test.append(
            DeliveryObservation.model_validate(
                row.model_dump()
                | {
                    "actual_travel_time_minutes": actual,
                    "exited_at": exited,
                    "recorded_at": exited + timedelta(seconds=2),
                    "label_available_at": exited + timedelta(seconds=2),
                }
            )
        )
    source = tmp_path / "changed-observations.csv"
    with source.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=OBSERVATION_COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(
            row.model_dump(mode="json")
            for row in (*data.split.train, *data.split.validation, *changed_test)
        )
    directory = tmp_path / "changed-dataset"
    export_observation_dataset(load_observations([source]), data.split.plan, directory)
    changed = load_observation_dataset(directory)
    assert changed.split.train == data.split.train
    assert changed.split.validation == data.split.validation
    assert changed.split.test != data.split.test
    assert changed.dataset_id != data.dataset_id
    output = tmp_path / "changed-artifact"
    train_observation_dataset(changed, output, graph)
    original_model, original_metadata = load_observation_model(original_artifact)
    changed_model, changed_metadata = load_observation_model(output)
    assert changed_metadata.selected_candidate == original_metadata.selected_candidate
    assert changed_metadata.artifact_sha256 == original_metadata.artifact_sha256
    assert changed_metadata.dataset_id == changed.dataset_id
    np.testing.assert_array_equal(
        changed_model.predict(feature_matrix(data.split.test)),
        original_model.predict(feature_matrix(data.split.test)),
    )


@pytest.mark.parametrize("partition", ["train", "validation", "test"])
def test_training_rejects_empty_partition_before_fitting_or_writing(
    observation_training_dataset, tmp_path, monkeypatch, partition
):
    data, _, graph = observation_training_dataset
    empty = replace(data, split=replace(data.split, **{partition: ()}))
    monkeypatch.setattr(
        train_observations,
        "train_candidates",
        lambda *args, **kwargs: pytest.fail("An incomplete dataset reached model fitting."),
    )
    output = tmp_path / "empty-artifact"
    with pytest.raises(ValueError):
        train_observation_dataset(empty, output, graph)
    assert not output.exists()


@pytest.mark.parametrize("partition", ["train", "validation", "test", "excluded"])
@pytest.mark.parametrize(
    "changes",
    [
        {"segment_id": "unknown-segment"},
        {"from_node": "G"},
        {"to_node": "G"},
        {"distance_km": 99.0},
        {"road_type": "highway"},
        {"reference_speed_kmh": 99.0},
    ],
)
def test_training_validates_graph_attributes_in_every_partition_before_fit(
    observation_training_dataset, tmp_path, monkeypatch, partition, changes
):
    data, _, graph = observation_training_dataset
    rows = getattr(data.split, partition) or data.split.train[:1]
    changed = DeliveryObservation.model_validate(rows[0].model_dump() | changes)
    incompatible = replace(data, split=replace(data.split, **{partition: (changed, *rows[1:])}))
    monkeypatch.setattr(
        train_observations,
        "train_candidates",
        lambda *args, **kwargs: pytest.fail("Graph-incompatible observations reached fitting."),
    )
    output = tmp_path / "incompatible-artifact"
    with pytest.raises(ValueError):
        train_observation_dataset(incompatible, output, graph)
    assert not output.exists()


def test_training_rejects_graph_version_mismatch_before_fitting(
    observation_training_dataset, tmp_path, monkeypatch
):
    data, _, graph = observation_training_dataset
    different = graph.model_copy(update={"graph_version": "different-graph"})
    monkeypatch.setattr(
        train_observations,
        "train_candidates",
        lambda *args, **kwargs: pytest.fail("A different graph reached model fitting."),
    )
    output = tmp_path / "different-graph"
    with pytest.raises(ValueError):
        train_observation_dataset(data, output, different)
    assert not output.exists()


@pytest.mark.parametrize("seed", [True, None, 42.0, -1, 2**32])
def test_training_rejects_invalid_seed_without_creating_artifact(
    observation_training_dataset, tmp_path, seed
):
    data, _, graph = observation_training_dataset
    output = tmp_path / "invalid-seed"
    with pytest.raises(ValueError):
        train_observation_dataset(data, output, graph, seed=seed)
    assert not output.exists()


def test_training_preserves_existing_artifact_without_fitting(
    observation_training_dataset, observation_model_bundle, monkeypatch
):
    data, _, graph = observation_training_dataset
    artifact, _, _ = observation_model_bundle
    before = artifact_contents(artifact)
    monkeypatch.setattr(
        train_observations,
        "train_candidates",
        lambda *args, **kwargs: pytest.fail("An existing artifact should fail before fitting."),
    )
    with pytest.raises(ValueError):
        train_observation_dataset(data, artifact, graph)
    assert artifact_contents(artifact) == before


def test_training_cli_writes_a_loadable_offline_bundle(
    observation_training_dataset, tmp_path, capsys
):
    data, directory, graph = observation_training_dataset
    graph_path = tmp_path / "graph.json"
    graph_path.write_text(graph.model_dump_json(), encoding="utf-8")
    output = tmp_path / "cli-artifact"
    args = [
        "--dataset",
        str(directory),
        "--graph",
        str(graph_path),
        "--output",
        str(output),
    ]
    main(args)
    console = json.loads(capsys.readouterr().out)
    _, metadata = load_observation_model(output)
    assert console["model_version"] == metadata.model_version
    assert console["dataset_id"] == data.dataset_id
    assert console["data_origin"] == "simulated"
    before = artifact_contents(output)
    with pytest.raises(SystemExit) as error:
        main(args)
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert artifact_contents(output) == before


@pytest.mark.parametrize("seed", ["-1", "4294967296", "not-a-number"])
def test_training_cli_rejects_invalid_seed_cleanly(
    observation_training_dataset, tmp_path, capsys, seed
):
    _, directory, graph = observation_training_dataset
    graph_path = tmp_path / "graph.json"
    graph_path.write_text(graph.model_dump_json(), encoding="utf-8")
    output = tmp_path / "invalid-seed"
    with pytest.raises(SystemExit) as error:
        main(
            [
                "--dataset",
                str(directory),
                "--graph",
                str(graph_path),
                "--output",
                str(output),
                "--seed",
                seed,
            ]
        )
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert not output.exists()


def test_training_cli_requires_an_explicit_graph(tmp_path, capsys):
    output = tmp_path / "missing-graph"
    with pytest.raises(SystemExit) as error:
        main(["--dataset", str(tmp_path), "--output", str(output)])
    assert error.value.code == 2
    assert "--graph" in capsys.readouterr().err
    assert not output.exists()
