import hashlib
import json

import pytest

from app.routing.demo import load_demo_graph
from training.dataset import export_dataset
from training.load_dataset import load_dataset
from training.serialization import json_bytes, payload_checksum
from training.synthetic import GeneratorConfig


@pytest.fixture
def dataset_directory(tmp_path):
    path = tmp_path / "dataset"
    export_dataset(
        load_demo_graph(),
        GeneratorConfig(train_days=1, validation_days=1, test_days=1, interval_minutes=360),
        path,
    )
    return path


def rewrite_manifest(path, mutate, rehash=False):
    manifest = json.loads((path / "manifest.json").read_bytes())
    mutate(manifest)
    if rehash:
        for name, metadata in manifest["files"].items():
            content = (path / name).read_bytes()
            metadata["bytes"] = len(content)
            metadata["sha256"] = hashlib.sha256(content).hexdigest()
        manifest["dataset_id"] = payload_checksum(
            {
                "graph_sha256": manifest["graph"]["sha256"],
                "configuration": manifest["configuration"],
                "files": manifest["files"],
            }
        )
    (path / "manifest.json").write_bytes(json_bytes(manifest))


def test_loader_preserves_validated_partitions_and_manifest_identity(dataset_directory):
    data = load_dataset(dataset_directory)
    assert len(data.split.train) == len(data.split.validation) == len(data.split.test) == 40
    assert data.dataset_id == data.manifest["dataset_id"]
    assert (
        data.manifest_sha256
        == hashlib.sha256((dataset_directory / "manifest.json").read_bytes()).hexdigest()
    )


@pytest.mark.parametrize("name", ["samples.csv", "train.csv", "validation.csv", "test.csv"])
def test_loader_rejects_corrupted_file(dataset_directory, name):
    path = dataset_directory / name
    path.write_bytes(path.read_bytes().replace(b"synthetic", b"corrupted", 1))
    with pytest.raises(ValueError, match="integrity"):
        load_dataset(dataset_directory)


def test_loader_rejects_rehashed_partition_leakage(dataset_directory):
    (dataset_directory / "validation.csv").write_bytes(
        (dataset_directory / "train.csv").read_bytes()
    )
    rewrite_manifest(dataset_directory, lambda _: None, rehash=True)
    with pytest.raises(ValueError, match="temporal/group"):
        load_dataset(dataset_directory)


@pytest.mark.parametrize(
    "mutate, message",
    [
        (lambda m: m["schema"]["features"].append("actual_travel_time_minutes"), "schema"),
        (lambda m: m.update(dataset_id="a" * 64), "identity"),
        (lambda m: m["files"].update({"../evil.csv": m["files"]["test.csv"]}), "five"),
        (lambda m: m["split_policy"].update(time_basis="planned_departure_at"), "policy"),
        (lambda m: m["partitions"]["train"].update(scenarios=999), "summary"),
    ],
)
def test_loader_rejects_incompatible_metadata(dataset_directory, mutate, message):
    rewrite_manifest(dataset_directory, mutate)
    with pytest.raises(ValueError, match=message):
        load_dataset(dataset_directory)


def test_loader_checks_schema_even_after_file_checksum_update(dataset_directory):
    path = dataset_directory / "train.csv"
    path.write_bytes(path.read_bytes().replace(b"distance_km,", b"actual_speed_kmh,", 1))
    rewrite_manifest(dataset_directory, lambda _: None, rehash=True)
    with pytest.raises(ValueError, match="CSV columns"):
        load_dataset(dataset_directory)


def test_loader_reports_missing_or_incomplete_dataset(tmp_path):
    with pytest.raises(ValueError, match="Unable to load dataset"):
        load_dataset(tmp_path)
