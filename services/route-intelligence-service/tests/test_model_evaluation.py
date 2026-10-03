import json
from unittest.mock import patch

import pytest
from sklearn.pipeline import Pipeline

from app.ml.artifacts import MODEL_FILE
from app.routing.demo import load_demo_graph
from training.evaluate import evaluate_model, main
from training.load_dataset import load_dataset


def test_evaluation_uses_test_only_without_fitting_or_changing_artifact(model_bundle):
    artifact, directory = model_bundle
    original = (artifact / MODEL_FILE).read_bytes()
    data = load_dataset(directory)
    with patch.object(Pipeline, "fit", side_effect=AssertionError("Evaluation must never fit")):
        report = evaluate_model(data, artifact, load_demo_graph())
    assert report["selected_model"]["overall"]["rows"] == len(data.split.test)
    assert report["valid_predictions"] is True
    assert report["selection_used_test"] is False
    assert report["model_refitted"] is False
    assert (
        report["selected_model"]["overall"]["mae_minutes"]
        < report["physical_reference"]["overall"]["mae_minutes"]
    )
    assert (artifact / MODEL_FILE).read_bytes() == original


def test_evaluation_rejects_another_dataset(model_bundle):
    artifact, directory = model_bundle
    data = load_dataset(directory)
    data.manifest["dataset_id"] = "a" * 64
    with pytest.raises(ValueError, match="dataset differs"):
        evaluate_model(data, artifact, load_demo_graph())


def test_command_exports_report_and_preserves_existing_file(model_bundle, tmp_path, capsys):
    artifact, directory = model_bundle
    output = tmp_path / "test-report.json"
    args = ["--artifact", str(artifact), "--dataset", str(directory), "--output", str(output)]
    main(args)
    report = json.loads(capsys.readouterr().out)
    assert json.loads(output.read_bytes()) == report
    original = output.read_bytes()
    with pytest.raises(SystemExit) as error:
        main(args)
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert output.read_bytes() == original


@pytest.mark.parametrize("extra", [["--origin", "G"], ["--origin", "A", "--destination", "A"]])
def test_evaluation_reports_unreachable_or_invalid_endpoints(model_bundle, tmp_path, capsys, extra):
    artifact, directory = model_bundle
    output = tmp_path / "report.json"
    with pytest.raises(SystemExit) as error:
        main(
            [
                "--artifact",
                str(artifact),
                "--dataset",
                str(directory),
                "--output",
                str(output),
                *extra,
            ]
        )
    assert error.value.code == 2
    assert not output.exists()
    assert "Traceback" not in capsys.readouterr().err
