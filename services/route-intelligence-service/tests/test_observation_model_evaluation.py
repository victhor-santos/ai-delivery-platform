import json
import math
from dataclasses import replace

import numpy as np
import pytest
from sklearn.pipeline import Pipeline

from app.ml.artifacts import load_observation_model
from app.ml.pipelines import feature_matrix
from training import evaluate_observation_model as observation_evaluation
from training import route_evaluation
from training.evaluate_observation_model import evaluate_observation_model, main
from training.metrics import evaluate_predictions


def test_saved_observation_model_is_evaluated_only_on_test_without_refitting_or_routing(
    observation_model_bundle, monkeypatch
):
    artifact, data, graph = observation_model_bundle
    before = {path.name: path.read_bytes() for path in artifact.iterdir()}
    matrices = []
    original = observation_evaluation.feature_matrix

    def capture(rows):
        matrices.append(tuple(rows))
        return original(rows)

    monkeypatch.setattr(observation_evaluation, "feature_matrix", capture)
    monkeypatch.setattr(
        Pipeline, "fit", lambda *args, **kwargs: pytest.fail("Evaluation refitted a model.")
    )
    monkeypatch.setattr(
        route_evaluation,
        "evaluate_routes",
        lambda *args, **kwargs: pytest.fail("Partial delivery exports cannot evaluate routes."),
    )
    report = evaluate_observation_model(data, artifact, graph)
    assert matrices == [data.split.test]
    assert report["report_version"] == "delivery-observation-test-report-v1"
    assert report["partition"] == "test"
    assert report["data_origin"] == "simulated"
    assert report["prediction_data_origin"] == "synthetic"
    assert report["dataset_id"] == data.dataset_id
    assert report["dataset_manifest_sha256"] == data.manifest_sha256
    assert report["test_rows"] == 24
    assert report["test_deliveries"] == 24
    assert report["selection_used_test"] is False
    assert report["model_refitted"] is False
    assert report["historical_predictions_recomputed"] is False
    assert report["valid_predictions"] is True
    assert "routing" not in report
    assert report["limitations"]
    latency = report["test_batch_latency"]
    assert latency["batch_rows"] == 24
    assert latency["repetitions"] == 5
    assert math.isfinite(latency["median_ms"]) and latency["median_ms"] >= 0
    assert {path.name: path.read_bytes() for path in artifact.iterdir()} == before


def test_evaluation_compares_selected_reference_and_original_snapshot_predictions(
    observation_model_bundle,
):
    artifact, data, graph = observation_model_bundle
    model, metadata = load_observation_model(artifact)
    samples = data.split.test
    report = evaluate_observation_model(data, artifact, graph)
    selected = model.predict(feature_matrix(samples))
    reference = np.array([row.distance_km / row.reference_speed_kmh * 60 for row in samples])
    stored = np.array([row.predicted_travel_time_minutes for row in samples])
    assert report["model_version"] == metadata.model_version
    assert report["selected_candidate"] == metadata.selected_candidate
    assert report["selected_model"] == evaluate_predictions(samples, selected)
    assert report["physical_reference"] == evaluate_predictions(samples, reference)
    assert report["stored_predictions"] == evaluate_predictions(samples, stored)
    known = report["stored_predictions"]["overall"]
    assert known["rows"] == 24
    assert known["invalid_predictions"] == 0
    assert known["mae_minutes"] == pytest.approx(1.5)
    assert known["rmse_minutes"] == pytest.approx(math.sqrt(2.5))
    assert set(report["historical_models"]) == {"historical-model-0", "historical-model-1"}
    for index in range(2):
        version = f"historical-model-{index}"
        subset = tuple(row for row in samples if row.model_version == version)
        predictions = np.array([row.predicted_travel_time_minutes for row in subset])
        metrics = report["historical_models"][version]
        assert metrics == evaluate_predictions(subset, predictions)
        assert metrics["overall"]["rows"] == 12
        assert metrics["overall"]["mae_minutes"] == pytest.approx(1 + index)
        assert metrics["overall"]["rmse_minutes"] == pytest.approx(1 + index)


def test_evaluation_rejects_empty_test_without_changing_the_artifact(observation_model_bundle):
    artifact, data, graph = observation_model_bundle
    empty = replace(data, split=replace(data.split, test=()))
    before = {path.name: path.read_bytes() for path in artifact.iterdir()}
    with pytest.raises(ValueError, match="(?i)test|nonempty"):
        evaluate_observation_model(empty, artifact, graph)
    assert {path.name: path.read_bytes() for path in artifact.iterdir()} == before


@pytest.mark.parametrize("field", ["dataset_id", "manifest_sha256"])
def test_evaluation_requires_the_original_dataset_and_manifest(observation_model_bundle, field):
    artifact, data, graph = observation_model_bundle
    changed = (
        replace(data, manifest=data.manifest | {"dataset_id": "a" * 64})
        if field == "dataset_id"
        else replace(data, manifest_sha256="b" * 64)
    )
    with pytest.raises(ValueError):
        evaluate_observation_model(changed, artifact, graph)


@pytest.mark.parametrize("change", ["version", "coordinates", "segment"])
def test_evaluation_requires_the_exact_original_graph(observation_model_bundle, change):
    artifact, data, graph = observation_model_bundle
    if change == "version":
        incompatible = graph.model_copy(update={"graph_version": "other-graph"})
    elif change == "coordinates":
        nodes = (graph.nodes[0].model_copy(update={"lat": -23.55}), *graph.nodes[1:])
        incompatible = graph.model_copy(update={"nodes": nodes})
    else:
        segments = (
            graph.segments[0].model_copy(update={"distance_km": 1.1}),
            *graph.segments[1:],
        )
        incompatible = graph.model_copy(update={"segments": segments})
    with pytest.raises(ValueError):
        evaluate_observation_model(data, artifact, incompatible)


@pytest.mark.parametrize("invalid", [float("nan"), 0.0, -1.0])
def test_invalid_selected_predictions_are_reported_without_hiding_the_error(
    observation_model_bundle, monkeypatch, invalid
):
    artifact, data, graph = observation_model_bundle
    model, metadata = load_observation_model(artifact)
    monkeypatch.setattr(model, "predict", lambda matrix: np.full(len(matrix), invalid))
    monkeypatch.setattr(
        observation_evaluation, "load_observation_model", lambda directory: (model, metadata)
    )
    report = evaluate_observation_model(data, artifact, graph)
    assert report["valid_predictions"] is False
    assert report["selected_model"]["overall"]["invalid_predictions"] == len(data.split.test)
    assert report["stored_predictions"]["overall"]["invalid_predictions"] == 0
    json.dumps(report, allow_nan=False)


def test_evaluation_reports_numeric_overflow_without_changing_the_artifact(
    observation_model_bundle, monkeypatch
):
    artifact, data, graph = observation_model_bundle
    before = {path.name: path.read_bytes() for path in artifact.iterdir()}
    model, metadata = load_observation_model(artifact)

    def overflow(matrix):
        return np.square(np.full(len(matrix), 1e308))

    monkeypatch.setattr(model, "predict", overflow)
    monkeypatch.setattr(
        observation_evaluation, "load_observation_model", lambda directory: (model, metadata)
    )
    with pytest.raises(ValueError, match="numeric range"):
        evaluate_observation_model(data, artifact, graph)
    assert {path.name: path.read_bytes() for path in artifact.iterdir()} == before


def evaluation_cli_args(dataset, artifact, graph_path, output):
    return [
        "--dataset",
        str(dataset),
        "--artifact",
        str(artifact),
        "--graph",
        str(graph_path),
        "--output",
        str(output),
    ]


def test_evaluation_cli_writes_report_and_preserves_existing_output_and_artifact(
    observation_training_dataset, observation_model_bundle, tmp_path, capsys
):
    data, directory, graph = observation_training_dataset
    artifact, _, _ = observation_model_bundle
    before = {path.name: path.read_bytes() for path in artifact.iterdir()}
    graph_path = tmp_path / "graph.json"
    graph_path.write_text(graph.model_dump_json(), encoding="utf-8")
    output = tmp_path / "test-report.json"
    args = evaluation_cli_args(directory, artifact, graph_path, output)
    main(args)
    console = json.loads(capsys.readouterr().out)
    report = json.loads(output.read_bytes())
    assert console == report
    assert report["dataset_id"] == data.dataset_id
    assert report["data_origin"] == "simulated"
    assert report["stored_predictions"]["overall"]["mae_minutes"] == pytest.approx(1.5)
    original = output.read_bytes()
    with pytest.raises(SystemExit) as error:
        main(args)
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert output.read_bytes() == original
    assert {path.name: path.read_bytes() for path in artifact.iterdir()} == before


def test_evaluation_cli_requires_an_explicit_graph(tmp_path, capsys):
    output = tmp_path / "missing-graph.json"
    with pytest.raises(SystemExit) as error:
        main(
            [
                "--dataset",
                str(tmp_path),
                "--artifact",
                str(tmp_path),
                "--output",
                str(output),
            ]
        )
    assert error.value.code == 2
    assert "--graph" in capsys.readouterr().err
    assert not output.exists()


@pytest.mark.parametrize("bad_input", ["dataset", "artifact", "graph"])
def test_evaluation_cli_fails_cleanly_without_writing_report_for_invalid_inputs(
    observation_training_dataset, observation_model_bundle, tmp_path, capsys, bad_input
):
    _, directory, graph = observation_training_dataset
    artifact, _, _ = observation_model_bundle
    graph_path = tmp_path / "graph.json"
    graph_path.write_text(graph.model_dump_json(), encoding="utf-8")
    values = {"dataset": directory, "artifact": artifact, "graph": graph_path}
    values[bad_input] = tmp_path / "missing-input"
    output = tmp_path / "invalid-report.json"
    with pytest.raises(SystemExit) as error:
        main(evaluation_cli_args(values["dataset"], values["artifact"], values["graph"], output))
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert not output.exists()
