import csv
import hashlib
import json

import pytest

from app.ml.features import FEATURE_COLUMNS
from app.routing.demo import load_demo_graph
from app.routing.graph import RoadGraph
from training.dataset import export_dataset, graph_checksum
from training.generate_dataset import main
from training.schema import SAMPLE_COLUMNS, TARGET_COLUMN
from training.synthetic import GeneratorConfig


@pytest.fixture
def config():
    return GeneratorConfig(train_days=1, validation_days=1, test_days=1, interval_minutes=360)


def test_export_is_byte_reproducible_and_all_checksums_match(config, tmp_path):
    graph = load_demo_graph()
    first, second = tmp_path / "first", tmp_path / "second"

    manifest = export_dataset(graph, config, first)
    assert export_dataset(graph, config, second) == manifest

    for path in first.iterdir():
        assert path.read_bytes() == (second / path.name).read_bytes()
        if path.suffix == ".csv":
            assert (
                hashlib.sha256(path.read_bytes()).hexdigest()
                == manifest["files"][path.name]["sha256"]
            )
            assert b"\r\n" not in path.read_bytes()
            with path.open(encoding="utf-8", newline="") as source:
                reader = csv.DictReader(source)
                assert tuple(reader.fieldnames) == SAMPLE_COLUMNS
                assert len(list(reader)) == manifest["files"][path.name]["rows"]
    assert json.loads((first / "manifest.json").read_text()) == manifest
    assert manifest["partitions"]["train"]["rows"] == 40
    assert manifest["partitions"]["validation"]["rows"] == 40
    assert manifest["partitions"]["test"]["rows"] == 40
    assert manifest["schema"]["features"] == list(FEATURE_COLUMNS)
    assert manifest["schema"]["target"] == TARGET_COLUMN
    assert TARGET_COLUMN not in manifest["schema"]["features"]
    assert manifest["audit"]["feature_target_signatures_in_multiple_partitions"] == 0


def test_graph_checksum_and_export_ignore_loading_order(config, tmp_path):
    graph = load_demo_graph()
    reversed_graph = RoadGraph.model_validate(
        dict(
            graph.model_dump(),
            nodes=tuple(reversed(graph.nodes)),
            segments=tuple(reversed(graph.segments)),
        )
    )

    assert graph_checksum(graph) == graph_checksum(reversed_graph)
    assert export_dataset(graph, config, tmp_path / "first") == export_dataset(
        reversed_graph, config, tmp_path / "second"
    )


def test_seed_changes_dataset_identity_and_sample_checksum(config, tmp_path):
    first = export_dataset(load_demo_graph(), config, tmp_path / "first")
    second = export_dataset(
        load_demo_graph(),
        GeneratorConfig.model_validate(dict(config.model_dump(), seed=43)),
        tmp_path / "second",
    )

    assert first["dataset_id"] != second["dataset_id"]
    assert first["files"]["samples.csv"]["sha256"] != second["files"]["samples.csv"]["sha256"]


def test_existing_output_is_preserved(config, tmp_path):
    path = tmp_path / "existing"
    path.mkdir()
    marker = path / "keep.txt"
    marker.write_text("preserve")

    with pytest.raises(ValueError, match="already exists"):
        export_dataset(load_demo_graph(), config, path)

    assert marker.read_text() == "preserve"
    assert list(path.iterdir()) == [marker]


def test_manifest_records_late_label_exclusions_for_whole_scenarios(tmp_path):
    config = GeneratorConfig(
        train_days=1,
        validation_days=1,
        test_days=1,
        interval_minutes=15,
        recording_delay_seconds=3600,
    )
    manifest = export_dataset(load_demo_graph(), config, tmp_path / "dataset")

    assert manifest["partitions"]["excluded"]["rows"] > 0
    assert manifest["partitions"]["excluded"]["rows"] == len(manifest["excluded_scenarios"]) * 10
    assert set(manifest["excluded_scenarios"].values()) == {"label_unavailable_at_cutoff"}
    assert (
        sum(data["rows"] for data in manifest["partitions"].values())
        == manifest["files"]["samples.csv"]["rows"]
    )


def test_empty_eligible_partition_is_rejected_before_creating_output(config, tmp_path):
    config = GeneratorConfig.model_validate(
        dict(config.model_dump(), interval_minutes=1440, label_delay_seconds=86400)
    )
    output = tmp_path / "dataset"

    with pytest.raises(ValueError, match="three partitions"):
        export_dataset(load_demo_graph(), config, output)

    assert not output.exists()


def test_audit_reports_exact_feature_target_repetitions_across_partitions(tmp_path):
    config = GeneratorConfig(
        train_days=7,
        validation_days=7,
        test_days=7,
        interval_minutes=360,
        noise_min=1.0,
        noise_max=1.0,
    )

    manifest = export_dataset(load_demo_graph(), config, tmp_path / "dataset")

    assert manifest["audit"]["feature_target_signatures_in_multiple_partitions"] > 0


def test_command_prints_summary_and_exports_manifest(tmp_path, capsys):
    output = tmp_path / "dataset"
    main(
        [
            "--output",
            str(output),
            "--train-days",
            "1",
            "--validation-days",
            "1",
            "--test-days",
            "1",
            "--interval-minutes",
            "360",
        ]
    )

    summary = json.loads(capsys.readouterr().out)

    assert summary["samples"] == 120
    assert summary["partitions"] == {"train": 40, "validation": 40, "test": 40, "excluded": 0}
    assert (output / "manifest.json").is_file()


@pytest.mark.parametrize(
    "args",
    [
        ["--seed", "-1"],
        ["--start-at", "2026-08-03T00:00:00"],
        ["--graph", "missing.json"],
    ],
)
def test_command_reports_controlled_errors_without_partial_output(tmp_path, capsys, args):
    output = tmp_path / "dataset"

    with pytest.raises(SystemExit) as exc:
        main(["--output", str(output), *args])

    assert exc.value.code == 2
    assert not output.exists()
    assert "Traceback" not in capsys.readouterr().err
