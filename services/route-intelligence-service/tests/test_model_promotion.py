import json
import shutil

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.ml.artifacts import MODEL_FILE, PROMOTION_REPORT_FILE, load_model
from app.ml.predictor import SegmentTravelTimePredictor
from app.routing.demo import load_demo_graph
from training.observation_dataset import export_observation_dataset, load_observation_dataset
from training.observations import load_observations
from training.serialization import json_bytes
from training.simulate_observations import (
    SimulationConfig,
    simulate_observations,
    write_observations,
)
from training.train_observations import train_observation_dataset
from training.validate_promotion import PromotionThresholds, main, promote, validate_promotion

BUNDLE_FILES = {MODEL_FILE, "metadata.json", "validation-report.json"}


@pytest.fixture(scope="module")
def promotion_inputs(model_bundle, tmp_path_factory):
    """A heavy-traffic change the small production model has not seen, over three weeks."""
    root = tmp_path_factory.mktemp("promotion")
    graph = load_demo_graph()
    config = SimulationConfig(train_days=7, validation_days=7, test_days=7, high_traffic_shift=1.6)
    predictor = SegmentTravelTimePredictor(*load_model(model_bundle[0]))
    export = root / "observations.csv"
    write_observations(simulate_observations(graph, predictor, config), export)
    dataset = root / "dataset"
    export_observation_dataset(load_observations([export]), config.split_plan, dataset)
    data = load_observation_dataset(dataset)
    candidate = root / "candidate"
    train_observation_dataset(data, candidate, graph)
    return candidate, model_bundle[0], data, dataset, graph


@pytest.fixture(scope="module")
def approved(promotion_inputs, tmp_path_factory):
    candidate, production, data, _, graph = promotion_inputs
    report = validate_promotion(candidate, production, data, graph, PromotionThresholds())
    output = tmp_path_factory.mktemp("approved") / "promoted"
    promote(report, candidate, output)
    return report, output


def test_candidate_that_passes_every_gate_is_copied_with_its_decision(approved):
    report, output = approved

    assert report["decision"] == "approved", report["failed_gates"]
    assert report["failed_gates"] == []
    assert {gate["name"] for gate in report["gates"]} == {
        "graph_compatibility",
        "feature_contract",
        "runtime_predictions",
        "test_rows",
        "segment_coverage",
        "traffic_coverage",
        "time_coverage",
        "valid_predictions",
        "improves_production",
        "beats_physical_reference",
        "no_slice_regression",
    }
    assert {path.name for path in output.iterdir()} == BUNDLE_FILES | {PROMOTION_REPORT_FILE}
    assert json.loads((output / PROMOTION_REPORT_FILE).read_bytes()) == report


def test_promoted_bundle_serves_routes_with_simulated_origin(approved):
    report, output = approved
    graph = load_demo_graph()
    body = {
        "origin": {"lat": graph.nodes_by_id["A"].lat, "lon": graph.nodes_by_id["A"].lon},
        "destination": {"lat": graph.nodes_by_id["C"].lat, "lon": graph.nodes_by_id["C"].lon},
        "departure_at": "2026-09-29T19:00:00-03:00",
    }
    settings = Settings(model_path=output, graph_path=None, traffic_path=None)

    with TestClient(create_app(settings)) as client:
        assert client.get("/health").json() == {"status": "UP"}
        route = client.post("/api/routes/fastest", json=body).json()

    assert route["model_version"] == report["candidate"]["model_version"]
    assert route["data_origin"] == "simulated"


def test_candidate_without_enough_improvement_is_rejected_without_bundle(
    promotion_inputs, tmp_path
):
    candidate, production, data, _, graph = promotion_inputs
    report = validate_promotion(
        candidate, production, data, graph, PromotionThresholds(min_relative_improvement=0.99)
    )
    output = tmp_path / "rejected"
    promote(report, candidate, output)

    assert report["decision"] == "rejected"
    assert report["failed_gates"] == ["improves_production"]
    assert {path.name for path in output.iterdir()} == {PROMOTION_REPORT_FILE}
    with pytest.raises(ValueError):
        load_model(output)


def test_insufficient_coverage_rejects_the_candidate(promotion_inputs):
    candidate, production, data, _, graph = promotion_inputs
    report = validate_promotion(
        candidate,
        production,
        data,
        graph,
        PromotionThresholds(min_test_rows=100_000, min_rows_per_segment=100_000),
    )

    assert report["decision"] == "rejected"
    assert {"test_rows", "segment_coverage"} <= set(report["failed_gates"])


@pytest.mark.parametrize(
    "change",
    [
        lambda report: report.update(decision="rejected"),
        lambda report: report["candidate"].update(metadata_sha256="0" * 64),
        lambda report: report["candidate"].update(artifact_sha256="0" * 64),
        lambda report: report.update(graph_sha256="0" * 64),
        lambda report: report.update(report_version="segment-model-promotion-report-v0"),
    ],
)
def test_runtime_rejects_a_promotion_that_does_not_match_the_bundle(approved, tmp_path, change):
    _, output = approved
    tampered = tmp_path / "tampered"
    shutil.copytree(output, tampered)
    report = json.loads((tampered / PROMOTION_REPORT_FILE).read_bytes())
    change(report)
    (tampered / PROMOTION_REPORT_FILE).write_bytes(json_bytes(report))

    with pytest.raises(ValueError):
        load_model(tampered)


def test_cli_exits_with_failure_and_keeps_the_report_when_rejected(model_bundle, tmp_path, capsys):
    from training.simulate_observations import main as simulate

    export = tmp_path / "observations.csv"
    simulate(
        [
            "--graph",
            "app/routing/data/synthetic-city-v1.json",
            "--production-model",
            str(model_bundle[0]),
            "--output",
            str(export),
            "--deliveries-per-day",
            "4",
        ]
    )
    capsys.readouterr()
    plan = SimulationConfig(deliveries_per_day=4).split_plan
    dataset = tmp_path / "dataset"
    export_observation_dataset(load_observations([export]), plan, dataset)
    candidate = tmp_path / "candidate"
    train_observation_dataset(load_observation_dataset(dataset), candidate, load_demo_graph())
    output = tmp_path / "promotion"
    arguments = [
        "--candidate",
        str(candidate),
        "--production-model",
        str(model_bundle[0]),
        "--dataset",
        str(dataset),
        "--graph",
        "app/routing/data/synthetic-city-v1.json",
        "--output",
        str(output),
    ]

    with pytest.raises(SystemExit) as exit_info:
        main(arguments)

    assert exit_info.value.code == 1
    printed = json.loads(capsys.readouterr().out)
    assert printed["decision"] == "rejected"
    assert "test_rows" in printed["failed_gates"]
    assert {path.name for path in output.iterdir()} == {PROMOTION_REPORT_FILE}

    with pytest.raises(SystemExit) as exit_info:
        main(arguments)
    assert exit_info.value.code == 2
    assert "already exists" in capsys.readouterr().err
