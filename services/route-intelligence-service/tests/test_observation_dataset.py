import csv
import hashlib
import io
import json
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID

import pytest

from app.ml.features import FEATURE_COLUMNS, departure_context
from training import observation_dataset
from training.observation_dataset import (
    export_observation_dataset,
    load_observation_dataset,
    split_observations,
)
from training.observations import OBSERVATION_COLUMNS, DeliveryObservation, load_observations
from training.prepare_observations import main
from training.serialization import json_bytes, payload_checksum
from training.splits import SplitPlan


@pytest.fixture
def plan():
    return SplitPlan(
        start_at=datetime(2026, 10, 3, tzinfo=UTC),
        train_end=datetime(2026, 10, 4, tzinfo=UTC),
        validation_end=datetime(2026, 10, 5, tzinfo=UTC),
        test_end=datetime(2026, 10, 6, tzinfo=UTC),
    )


def observation_at(prediction, delivery=1, sequence=0, **changes):
    entered = prediction + timedelta(minutes=1 + sequence * 3)
    exited = entered + timedelta(minutes=1)
    recorded = prediction + timedelta(minutes=10 + sequence)
    hour, weekday = departure_context(prediction)
    values = {
        "schema_version": "delivery-segment-observation-v1",
        "traversal_id": UUID(int=delivery * 1000 + sequence),
        "delivery_id": UUID(int=delivery),
        "route_plan_id": UUID(int=delivery * 100),
        "sequence": sequence,
        "data_origin": "simulated",
        "prediction_data_origin": "synthetic",
        "segment_id": f"segment-{sequence}",
        "from_node": chr(65 + sequence),
        "to_node": chr(66 + sequence),
        "graph_version": "graph-v1",
        "model_version": "model-v1",
        "feature_schema_version": "segment-features-v1",
        "distance_km": 0.9 + sequence,
        "road_type": "residential",
        "reference_speed_kmh": 15.0,
        "traffic_level": "low",
        "hour": hour,
        "day_of_week": weekday,
        "timezone": "America/Sao_Paulo",
        "traffic_source": "synthetic-traffic-v1",
        "traffic_observed_at": prediction - timedelta(minutes=1),
        "traffic_available_at": prediction,
        "features_available_at": prediction,
        "prediction_at": prediction,
        "planned_departure_at": prediction,
        "context_as_of": prediction,
        "predicted_travel_time_minutes": 2.0,
        "entered_at": entered,
        "entry_recorded_at": entered + timedelta(seconds=1),
        "exited_at": exited,
        "recorded_at": recorded,
        "label_available_at": recorded,
        "actual_travel_time_minutes": 1.0,
    }
    return DeliveryObservation.model_validate(values | changes)


def write_rows(path, rows):
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=OBSERVATION_COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(row.model_dump(mode="json") for row in rows)
    return path


def loaded_rows(tmp_path, rows, name="observations.csv"):
    return load_observations([write_rows(tmp_path / name, rows)])


@pytest.fixture
def data(tmp_path, plan):
    rows = [
        observation_at(prediction, delivery, sequence)
        for delivery, prediction in enumerate(
            (plan.start_at, plan.train_end, plan.validation_end), start=1
        )
        for sequence in (0, 1)
    ]
    return loaded_rows(tmp_path, rows)


@pytest.fixture
def dataset_directory(tmp_path, data, plan):
    output = tmp_path / "dataset"
    export_observation_dataset(data, plan, output)
    return output


def read_manifest(directory):
    return json.loads((directory / "manifest.json").read_bytes())


def rewrite_manifest(directory, mutate):
    manifest = read_manifest(directory)
    mutate(manifest)
    (directory / "manifest.json").write_bytes(json_bytes(manifest))


def rehash_manifest_files(directory):
    manifest = read_manifest(directory)
    for name, metadata in manifest["files"].items():
        content = (directory / name).read_bytes()
        reader = csv.DictReader(io.StringIO(content.decode("utf-8")))
        metadata["rows"] = sum(1 for _ in reader)
        metadata["bytes"] = len(content)
        metadata["sha256"] = hashlib.sha256(content).hexdigest()
    manifest["dataset_id"] = payload_checksum(observation_dataset._dataset_identity(manifest))
    (directory / "manifest.json").write_bytes(json_bytes(manifest))


def test_java_export_roundtrips_without_inventing_missing_partitions(tmp_path, plan):
    fixture = Path(__file__).parent / "fixtures" / "delivery-observations-v1.csv"
    data = load_observations([fixture])
    original = fixture.read_bytes()
    output = tmp_path / "java-dataset"
    manifest = export_observation_dataset(data, plan, output)
    restored = load_observation_dataset(output)
    assert restored.split.train == data.rows
    assert not restored.split.validation and not restored.split.test
    assert manifest["audit"]["all_partitions_nonempty"] is False
    assert manifest["audit"]["empty_partitions"] == ["validation", "test"]
    assert manifest["partitions"]["train"]["rows"] == 2
    assert manifest["partitions"]["train"]["deliveries"] == 1
    assert manifest["data_origin"] == "simulated"
    assert manifest["prediction_data_origin"] == "synthetic"
    assert fixture.read_bytes() == original


def test_split_keeps_deliveries_whole_and_labels_available_at_each_cutoff(data, plan):
    split = split_observations(data, plan)
    identifiers = []
    for name, cutoff in (
        ("train", plan.train_end),
        ("validation", plan.validation_end),
        ("test", plan.test_end),
    ):
        rows = getattr(split, name)
        assert len(rows) == 2
        assert {row.sequence for row in rows} == {0, 1}
        assert max(row.label_available_at for row in rows) <= cutoff
        identifiers.append({row.delivery_id for row in rows})
    assert identifiers[0].isdisjoint(identifiers[1] | identifiers[2])
    assert identifiers[1].isdisjoint(identifiers[2])
    assert not split.excluded


@pytest.mark.parametrize(
    "prediction_name, expected_partition",
    [("start_at", "train"), ("train_end", "validation"), ("validation_end", "test")],
)
def test_prediction_on_boundary_uses_the_next_partition(
    tmp_path, plan, prediction_name, expected_partition
):
    row = observation_at(getattr(plan, prediction_name))
    split = split_observations(loaded_rows(tmp_path, [row]), plan)
    assert getattr(split, expected_partition) == (row,)
    assert sum(len(rows) for rows in split.subsets().values()) == 1


@pytest.mark.parametrize("cutoff_name", ["train_end", "validation_end", "test_end"])
def test_label_on_partition_end_is_accepted(tmp_path, plan, cutoff_name):
    cutoff = getattr(plan, cutoff_name)
    row = observation_at(cutoff - timedelta(hours=1), recorded_at=cutoff, label_available_at=cutoff)
    split = split_observations(loaded_rows(tmp_path, [row]), plan)
    assert not split.excluded
    assert sum(len(getattr(split, name)) for name in ("train", "validation", "test")) == 1


@pytest.mark.parametrize("cutoff_name", ["train_end", "validation_end", "test_end"])
def test_one_late_label_excludes_the_entire_delivery(tmp_path, plan, cutoff_name):
    cutoff = getattr(plan, cutoff_name)
    prediction = cutoff - timedelta(hours=1)
    first = observation_at(prediction)
    late = cutoff + timedelta(microseconds=1)
    second = observation_at(prediction, sequence=1, recorded_at=late, label_available_at=late)
    split = split_observations(loaded_rows(tmp_path, [first, second]), plan)
    assert split.excluded == (first, second)
    assert not split.train and not split.validation and not split.test
    assert split.excluded_reasons == {str(first.delivery_id): "label_unavailable_at_cutoff"}


@pytest.mark.parametrize("outside", ["before_start", "at_test_end"])
def test_delivery_outside_period_is_preserved_as_excluded(tmp_path, plan, outside):
    prediction = (
        plan.start_at - timedelta(microseconds=1) if outside == "before_start" else plan.test_end
    )
    row = observation_at(prediction)
    split = split_observations(loaded_rows(tmp_path, [row]), plan)
    assert split.excluded == (row,)
    assert split.excluded_reasons == {str(row.delivery_id): "outside_period"}


def test_split_uses_prediction_instead_of_planned_departure(tmp_path, plan):
    prediction = plan.train_end + timedelta(minutes=1)
    planned = prediction - timedelta(hours=1)
    hour, weekday = departure_context(planned)
    row = observation_at(prediction, planned_departure_at=planned, hour=hour, day_of_week=weekday)
    split = split_observations(loaded_rows(tmp_path, [row]), plan)
    assert split.validation == (row,)
    assert not split.train


def test_partial_delivery_and_different_historical_models_are_preserved(tmp_path, plan):
    rows = [
        observation_at(plan.start_at, sequence=2),
        observation_at(plan.train_end, delivery=2, model_version="newer-model"),
    ]
    data = loaded_rows(tmp_path, rows)
    output = tmp_path / "partial"
    manifest = export_observation_dataset(data, plan, output)
    restored = load_observation_dataset(output)
    assert restored.split.train == (rows[0],)
    assert restored.split.validation == (rows[1],)
    assert "model-v1" in manifest["partitions"]["train"]["model_versions"]
    assert "newer-model" in manifest["partitions"]["validation"]["model_versions"]


def test_different_graph_versions_are_not_combined(tmp_path, plan):
    rows = [
        observation_at(plan.start_at),
        observation_at(plan.train_end, delivery=2, graph_version="other-graph"),
    ]
    data = loaded_rows(tmp_path, rows)
    with pytest.raises(ValueError):
        split_observations(data, plan)
    output = tmp_path / "mixed-graphs"
    with pytest.raises(ValueError):
        export_observation_dataset(data, plan, output)
    assert not output.exists()


@pytest.mark.parametrize(
    "changes",
    [
        {"from_node": "X"},
        {"to_node": "Y"},
        {"distance_km": 1.1},
        {"reference_speed_kmh": 20.0},
        {"road_type": "primary"},
    ],
)
def test_same_graph_segment_cannot_change_static_attributes(tmp_path, plan, changes):
    rows = [
        observation_at(plan.start_at),
        observation_at(plan.train_end, delivery=2, **changes),
    ]
    with pytest.raises(ValueError):
        split_observations(loaded_rows(tmp_path, rows), plan)


def test_dataset_rejects_empty_input_before_creating_output(tmp_path, plan):
    data = loaded_rows(tmp_path, [])
    with pytest.raises(ValueError):
        split_observations(data, plan)
    output = tmp_path / "empty"
    with pytest.raises(ValueError):
        export_observation_dataset(data, plan, output)
    assert not output.exists()


def test_all_excluded_deliveries_roundtrip_with_reasons_and_empty_partition_audit(tmp_path, plan):
    row = observation_at(plan.test_end)
    data = loaded_rows(tmp_path, [row])
    output = tmp_path / "excluded-only"
    manifest = export_observation_dataset(data, plan, output)
    restored = load_observation_dataset(output)
    assert restored.split.excluded == (row,)
    assert manifest["excluded_deliveries"] == {str(row.delivery_id): "outside_period"}
    assert manifest["audit"]["all_partitions_nonempty"] is False
    assert manifest["audit"]["empty_partitions"] == ["train", "validation", "test"]
    assert manifest["partitions"]["excluded"]["deliveries"] == 1


def test_export_preserves_exact_schema_provenance_and_integrity(dataset_directory, data, plan):
    manifest = read_manifest(dataset_directory)
    assert manifest["manifest_version"] == "delivery-observation-dataset-v1"
    assert manifest["observation_set_id"] == data.observation_set_id
    assert manifest["schema"]["features"] == list(FEATURE_COLUMNS)
    assert manifest["schema"]["target"] == "actual_travel_time_minutes"
    assert manifest["split_policy"]["group_by"] == "delivery_id"
    assert manifest["split_policy"]["time_basis"] == "prediction_at"
    assert manifest["split_policy"]["boundaries"] == plan.model_dump(mode="json")
    assert manifest["audit"]["all_partitions_nonempty"] is True
    assert manifest["audit"]["empty_partitions"] == []
    assert manifest["audit"]["delivery_ids_in_multiple_partitions"] == 0
    assert manifest["duplicate_rows"] == 0
    assert manifest["sources"][0]["sha256"] == data.sources[0].sha256
    assert set(manifest["files"]) == {
        "samples.csv",
        "train.csv",
        "validation.csv",
        "test.csv",
        "excluded.csv",
    }
    for name, metadata in manifest["files"].items():
        content = (dataset_directory / name).read_bytes()
        reader = csv.DictReader(io.StringIO(content.decode("utf-8")))
        assert reader.fieldnames == OBSERVATION_COLUMNS
        assert sum(1 for _ in reader) == metadata["rows"]
        assert metadata["bytes"] == len(content)
        assert metadata["sha256"] == hashlib.sha256(content).hexdigest()
    restored = load_observation_dataset(dataset_directory)
    assert restored.dataset_id == manifest["dataset_id"]
    assert restored.manifest == manifest
    assert restored.split == split_observations(data, plan)
    assert (
        restored.manifest_sha256
        == hashlib.sha256((dataset_directory / "manifest.json").read_bytes()).hexdigest()
    )


def test_normalized_identity_is_independent_of_row_order_source_names_and_duplicates(
    tmp_path, data, plan
):
    first = write_rows(tmp_path / "first.csv", data.rows)
    second = write_rows(tmp_path / "second.csv", tuple(reversed(data.rows)))
    repeated = load_observations([second, first])
    original = export_observation_dataset(data, plan, tmp_path / "original")
    reordered = export_observation_dataset(repeated, plan, tmp_path / "repeated")
    assert repeated.duplicate_rows == len(data.rows)
    assert reordered["duplicate_rows"] == len(data.rows)
    assert original["dataset_id"] == reordered["dataset_id"]
    assert original["files"] == reordered["files"]
    assert original["sources"] != reordered["sources"]


def test_equivalent_boundary_offsets_do_not_change_dataset_identity(tmp_path, data, plan):
    offset = timezone(timedelta(hours=-3))
    offset_plan = SplitPlan.model_validate(
        {name: value.astimezone(offset) for name, value in plan.model_dump().items()}
    )
    first = export_observation_dataset(data, plan, tmp_path / "utc")
    second = export_observation_dataset(data, offset_plan, tmp_path / "offset")
    assert json_bytes(first) == json_bytes(second)


def test_changed_temporal_policy_changes_dataset_identity(tmp_path, data, plan):
    later = plan.model_copy(update={"train_end": plan.train_end + timedelta(hours=1)})
    first = export_observation_dataset(data, plan, tmp_path / "first-plan")
    second = export_observation_dataset(data, later, tmp_path / "later-plan")
    assert first["observation_set_id"] == second["observation_set_id"]
    assert first["dataset_id"] != second["dataset_id"]


def test_export_never_overwrites_existing_directory(dataset_directory, data, plan):
    before = {path.name: path.read_bytes() for path in dataset_directory.iterdir()}
    with pytest.raises(ValueError):
        export_observation_dataset(data, plan, dataset_directory)
    assert {path.name: path.read_bytes() for path in dataset_directory.iterdir()} == before


def test_export_rejects_output_that_cannot_be_loaded_under_the_csv_limit(
    tmp_path, data, plan, monkeypatch
):
    monkeypatch.setattr(observation_dataset, "MAX_EXPORT_BYTES", 5)
    output = tmp_path / "too-large"
    with pytest.raises(ValueError):
        export_observation_dataset(data, plan, output)
    assert not (output / "manifest.json").exists()


@pytest.mark.parametrize(
    "name", ["samples.csv", "train.csv", "validation.csv", "test.csv", "excluded.csv"]
)
def test_loader_rejects_corrupted_csv(dataset_directory, name):
    path = dataset_directory / name
    path.write_bytes(path.read_bytes() + b"unexpected\n")
    with pytest.raises(ValueError):
        load_observation_dataset(dataset_directory)


@pytest.mark.parametrize(
    "mutate",
    [
        lambda m: m.update(data_origin="synthetic"),
        lambda m: m.update(prediction_data_origin="simulated"),
        lambda m: m.update(dataset_id="a" * 64),
        lambda m: m.update(observation_set_id="a" * 64),
        lambda m: m["schema"]["features"].append("predicted_travel_time_minutes"),
        lambda m: m["schema"].update(target="predicted_travel_time_minutes"),
        lambda m: m["split_policy"].update(time_basis="entered_at"),
        lambda m: m["split_policy"].update(group_by="segment_id"),
        lambda m: m["partitions"]["train"].update(deliveries=99),
        lambda m: m["partitions"]["train"].update(deliveries=True),
        lambda m: m["partitions"]["train"].update(model_versions={"invented-model": 2}),
        lambda m: m["audit"].update(delivery_ids_in_multiple_partitions=1),
        lambda m: m["audit"].update(delivery_ids_in_multiple_partitions=False),
        lambda m: m["audit"].update(all_partitions_nonempty=False),
        lambda m: m["audit"].update(all_partitions_nonempty=1),
        lambda m: m.update(excluded_deliveries={str(UUID(int=1)): "outside_period"}),
        lambda m: m["files"].update({"../outside.csv": m["files"]["train.csv"]}),
    ],
)
def test_loader_rejects_incompatible_or_false_metadata(dataset_directory, mutate):
    rewrite_manifest(dataset_directory, mutate)
    with pytest.raises(ValueError):
        load_observation_dataset(dataset_directory)


def test_loader_reconstructs_temporal_split_even_when_partition_hashes_are_updated(
    dataset_directory,
):
    (dataset_directory / "validation.csv").write_bytes(
        (dataset_directory / "train.csv").read_bytes()
    )
    rehash_manifest_files(dataset_directory)
    with pytest.raises(ValueError, match="(?i)partition|temporal|policy"):
        load_observation_dataset(dataset_directory)


def test_loader_checks_versioned_columns_after_checksum_and_identity_update(dataset_directory):
    path = dataset_directory / "train.csv"
    path.write_bytes(path.read_bytes().replace(b"distance_km,", b"actual_speed_kmh,", 1))
    rehash_manifest_files(dataset_directory)
    with pytest.raises(ValueError, match="(?i)column|schema"):
        load_observation_dataset(dataset_directory)


def test_loader_does_not_count_samples_again_when_validating_partitions(
    dataset_directory, data, monkeypatch
):
    from training import observations

    monkeypatch.setattr(observations, "MAX_OBSERVATIONS", len(data.rows))
    assert load_observation_dataset(dataset_directory).split.train == data.rows[:2]


def test_loader_reports_missing_or_incomplete_dataset(tmp_path):
    with pytest.raises(ValueError):
        load_observation_dataset(tmp_path)


def cli_args(input_path, plan, output):
    return [
        "--input",
        str(input_path),
        "--start-at",
        plan.start_at.isoformat(),
        "--train-end",
        plan.train_end.isoformat(),
        "--validation-end",
        plan.validation_end.isoformat(),
        "--test-end",
        plan.test_end.isoformat(),
        "--output",
        str(output),
    ]


def test_cli_exports_manifest_and_preserves_input_and_existing_output(tmp_path, data, plan, capsys):
    source = write_rows(tmp_path / "cli-input.csv", data.rows)
    original = source.read_bytes()
    output = tmp_path / "cli-dataset"
    args = cli_args(source, plan, output)
    main(args)
    console = json.loads(capsys.readouterr().out)
    manifest = read_manifest(output)
    assert console["dataset_id"] == manifest["dataset_id"]
    assert console["data_origin"] == "simulated"
    assert console["partitions"] == manifest["partitions"]
    assert console["audit"] == manifest["audit"]
    assert load_observation_dataset(output).split == split_observations(data, plan)
    before = (output / "manifest.json").read_bytes()
    with pytest.raises(SystemExit) as error:
        main(args)
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert source.read_bytes() == original
    assert (output / "manifest.json").read_bytes() == before


@pytest.mark.parametrize("value", ["bad-date", "2026-10-03T00:00:00", "1790985600"])
def test_cli_rejects_invalid_boundary_without_creating_output(tmp_path, data, plan, capsys, value):
    source = write_rows(tmp_path / "invalid-boundary.csv", data.rows)
    output = tmp_path / "invalid-boundary"
    args = cli_args(source, plan, output)
    args[args.index("--start-at") + 1] = value
    with pytest.raises(SystemExit) as error:
        main(args)
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert not output.exists()


def test_cli_rejects_non_increasing_boundaries(tmp_path, data, plan, capsys):
    source = write_rows(tmp_path / "invalid-plan.csv", data.rows)
    output = tmp_path / "invalid-plan"
    args = cli_args(source, plan, output)
    args[args.index("--train-end") + 1] = plan.validation_end.isoformat()
    with pytest.raises(SystemExit) as error:
        main(args)
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert not output.exists()


@pytest.mark.parametrize("origin", ["synthetic", "real"])
def test_cli_rejects_foreign_data_origin_before_creating_output(
    tmp_path, data, plan, capsys, origin
):
    source = write_rows(tmp_path / "foreign-origin.csv", data.rows)
    source.write_bytes(source.read_bytes().replace(b",simulated,", f",{origin},".encode()))
    output = tmp_path / "foreign-origin"
    with pytest.raises(SystemExit) as error:
        main(cli_args(source, plan, output))
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert not output.exists()
