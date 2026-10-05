import csv
import hashlib
import json
import math
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID

import pytest

from training import observations
from training.evaluate_observations import evaluate_observations, main
from training.observations import OBSERVATION_COLUMNS, load_observations
from training.serialization import json_bytes

NOW = datetime(2026, 10, 3, 12, tzinfo=UTC)
CUTOFF = NOW + timedelta(hours=3)


def test_reads_the_export_captured_from_the_java_api():
    fixture = Path(__file__).parent / "fixtures" / "delivery-observations-v1.csv"
    data = load_observations([fixture])
    report = evaluate_observations(data, datetime(2026, 10, 4, tzinfo=UTC))
    assert report["included_rows"] == 2
    assert report["included_deliveries"] == 1
    assert report["groups"][0]["graph_version"] == "synthetic-city-v1"
    assert report["groups"][0]["overall"]["invalid_predictions"] == 0
    assert [row.sequence for row in data.rows] == [0, 1]
    assert all(row.data_origin == "simulated" for row in data.rows)


def sample(sequence=0, duration=1.0, prediction=2.0, delivery=1, **changes):
    entered = NOW + timedelta(seconds=60 + sequence * 180)
    available = NOW + timedelta(hours=2, seconds=sequence)
    row = {
        "schema_version": "delivery-segment-observation-v1",
        "traversal_id": str(UUID(int=1000 * delivery + sequence)),
        "delivery_id": str(UUID(int=delivery)),
        "route_plan_id": str(UUID(int=100 + delivery)),
        "sequence": sequence,
        "data_origin": "simulated",
        "prediction_data_origin": "synthetic",
        "segment_id": f"segment-{sequence}",
        "from_node": chr(65 + sequence),
        "to_node": chr(66 + sequence),
        "graph_version": "graph-v1",
        "model_version": "model-v1",
        "feature_schema_version": "segment-features-v1",
        "distance_km": 0.9,
        "road_type": "residential",
        "reference_speed_kmh": 15.0,
        "traffic_level": "low",
        "hour": 9,
        "day_of_week": 5,
        "timezone": "America/Sao_Paulo",
        "traffic_source": "synthetic-traffic-v1",
        "traffic_observed_at": NOW.isoformat(),
        "traffic_available_at": NOW.isoformat(),
        "features_available_at": NOW.isoformat(),
        "prediction_at": NOW.isoformat(),
        "planned_departure_at": NOW.isoformat(),
        "context_as_of": NOW.isoformat(),
        "predicted_travel_time_minutes": prediction,
        "entered_at": entered.isoformat(),
        "entry_recorded_at": (NOW + timedelta(hours=1)).isoformat(),
        "exited_at": (entered + timedelta(minutes=duration)).isoformat(),
        "recorded_at": available.isoformat(),
        "label_available_at": available.isoformat(),
        "actual_travel_time_minutes": duration,
    }
    return row | changes


def write_export(tmp_path, rows, name="observations.csv", columns=OBSERVATION_COLUMNS):
    path = tmp_path / name
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=columns, quoting=csv.QUOTE_ALL)
        writer.writeheader()
        writer.writerows(rows)
    return path


def test_scores_original_predictions_and_preserves_provenance_and_source_checksums(tmp_path):
    path = write_export(tmp_path, [sample(), sample(1, 2.0, 2.0), sample(2, 3.0, 5.0)])
    data = load_observations([path])
    report = evaluate_observations(data, CUTOFF)
    assert report["data_origin"] == "simulated"
    assert report["prediction_data_origin"] == "synthetic"
    assert report["model_refitted"] is report["predictions_recomputed"] is False
    assert report["included_rows"] == 3
    assert report["included_deliveries"] == 1
    scores = report["groups"][0]
    assert scores["model_version"] == "model-v1"
    assert scores["overall"]["mae_minutes"] == 1
    assert scores["overall"]["rmse_minutes"] == pytest.approx(math.sqrt(5 / 3))
    assert scores["overall"]["r2"] == pytest.approx(-1.5)
    assert scores["by_group"]["road_type"]["residential"]["rows"] == 3
    assert report["sources"][0]["sha256"] == hashlib.sha256(path.read_bytes()).hexdigest()
    assert report["sources"][0]["bytes"] == path.stat().st_size


def test_cutoff_uses_label_availability_not_old_event_time_and_is_inclusive(tmp_path):
    path = write_export(tmp_path, [sample(), sample(1)])
    data = load_observations([path])
    cutoff = NOW + timedelta(hours=2)
    before = evaluate_observations(data, cutoff - timedelta(microseconds=1))
    assert before["groups"] == []
    assert before["excluded_after_cutoff"] == 2
    at_cutoff = evaluate_observations(data, cutoff)
    assert at_cutoff["included_rows"] == at_cutoff["excluded_after_cutoff"] == 1
    assert at_cutoff["groups"][0]["overall"]["r2"] is None


def test_repeated_exports_are_deduplicated_with_order_independent_results(tmp_path):
    first = write_export(tmp_path, [sample(1), sample()], "first.csv")
    second = write_export(tmp_path, [sample(), sample(1), sample(2)], "second.csv")
    data = load_observations([first, second])
    report = evaluate_observations(data, CUTOFF)
    assert report["input_rows"] == 5
    assert report["duplicate_rows"] == 2
    assert report["unique_rows"] == 3
    assert json_bytes(report) == json_bytes(
        evaluate_observations(load_observations([second, first]), CUTOFF)
    )


def test_equivalent_offsets_preserve_identity_deduplication_and_cutoff(tmp_path):
    utc_row = sample()
    offset_row = dict(utc_row)
    for column, value in offset_row.items():
        if column.endswith("_at") or column == "context_as_of":
            offset_row[column] = (
                datetime.fromisoformat(value).astimezone(timezone(timedelta(hours=-3))).isoformat()
            )
    first = write_export(tmp_path, [utc_row], "utc.csv")
    second = write_export(tmp_path, [offset_row], "offset.csv")
    utc_data = load_observations([first])
    offset_data = load_observations([second])
    assert utc_data.observation_set_id == offset_data.observation_set_id
    combined = load_observations([first, second])
    assert combined.duplicate_rows == 1
    cutoff = NOW + timedelta(hours=2)
    utc_report = evaluate_observations(combined, cutoff)
    offset_report = evaluate_observations(
        combined, cutoff.astimezone(timezone(timedelta(hours=-3)))
    )
    assert json_bytes(utc_report) == json_bytes(offset_report)
    assert utc_report["included_rows"] == 1


@pytest.mark.parametrize(
    "changes, message",
    [
        ({"predicted_travel_time_minutes": 3}, "traversal ID"),
        ({"traversal_id": str(UUID(int=99))}, "delivery sequence"),
    ],
)
def test_conflicting_duplicates_fail_even_when_after_the_report_cutoff(tmp_path, changes, message):
    path = write_export(tmp_path, [sample(), sample(**changes)])
    with pytest.raises(ValueError, match=message):
        evaluate_observations(load_observations([path]), NOW)


@pytest.mark.parametrize(
    "column, value",
    [
        ("schema_version", "unknown"),
        ("feature_schema_version", "unknown"),
        ("data_origin", "real"),
        ("prediction_data_origin", "simulated"),
        ("traversal_id", "not-a-uuid"),
        ("sequence", 199),
        ("sequence", -1),
        ("hour", "9.0"),
        ("hour", 10),
        ("day_of_week", 7),
        ("model_version", "m" * 129),
        ("road_type", "unknown"),
        ("traffic_level", "unknown"),
        ("reference_speed_kmh", 0),
        ("predicted_travel_time_minutes", "NaN"),
        ("predicted_travel_time_minutes", "inf"),
        ("predicted_travel_time_minutes", 0),
        ("actual_travel_time_minutes", 0),
        ("actual_travel_time_minutes", 2),
        ("timezone", "UTC"),
        ("from_node", "B"),
        ("features_available_at", (NOW + timedelta(seconds=1)).isoformat()),
        ("label_available_at", NOW.isoformat()),
        ("recorded_at", (CUTOFF + timedelta(hours=1)).isoformat()),
        ("entered_at", "2026-10-03T12:01:00"),
        ("entered_at", "1791028800"),
        ("exited_at", ""),
    ],
)
def test_rejects_corrupt_rows_without_silently_dropping_them(tmp_path, column, value):
    path = write_export(tmp_path, [sample(**{column: value})])
    with pytest.raises(ValueError, match="Unable to load observation export"):
        load_observations([path])


@pytest.mark.parametrize(
    "changes, message",
    [
        ({"model_version": "other-model"}, "conflicting prediction"),
        ({"route_plan_id": str(UUID(int=55))}, "conflicting prediction"),
        ({"from_node": "X"}, "connected path"),
        (
            {
                "entered_at": (NOW + timedelta(seconds=90)).isoformat(),
                "exited_at": (NOW + timedelta(seconds=150)).isoformat(),
            },
            "overlap",
        ),
    ],
)
def test_rejects_inconsistent_journey_histories(tmp_path, changes, message):
    path = write_export(tmp_path, [sample(), sample(1, **changes)])
    with pytest.raises(ValueError, match=message):
        load_observations([path])


def test_groups_model_graph_versions_and_accepts_partial_routes(tmp_path):
    path = write_export(
        tmp_path,
        [
            sample(2, model_version="m" * 128),
            sample(delivery=2, graph_version="another-graph"),
            sample(delivery=3),
        ],
    )
    report = evaluate_observations(load_observations([path]), CUTOFF)
    assert len(report["groups"]) == 3
    assert report["included_deliveries"] == 3
    assert all(group["overall"]["r2"] is None for group in report["groups"])
    assert any("part of a route" in item for item in report["limitations"])


def test_planned_departure_may_differ_from_observed_entry(tmp_path):
    path = write_export(
        tmp_path, [sample(planned_departure_at=(NOW + timedelta(hours=1)).isoformat(), hour=10)]
    )
    assert len(load_observations([path]).rows) == 1


@pytest.mark.parametrize("header", [[], list(reversed(OBSERVATION_COLUMNS)), ["schema_version"]])
def test_rejects_wrong_headers(tmp_path, header):
    path = write_export(tmp_path, [], columns=header)
    with pytest.raises(ValueError, match="Columns"):
        load_observations([path])


def test_accepts_header_only_and_utf8_bom_exports(tmp_path):
    path = write_export(tmp_path, [])
    path.write_bytes(b"\xef\xbb\xbf" + path.read_bytes())
    report = evaluate_observations(load_observations([path]), CUTOFF)
    assert report["unique_rows"] == report["included_rows"] == 0
    assert report["groups"] == []


@pytest.mark.parametrize("extra", [",unexpected", ",", '"'])
def test_rejects_extra_cells_and_malformed_quotes(tmp_path, extra):
    path = write_export(tmp_path, [sample()])
    path.write_bytes(path.read_bytes().rstrip(b"\r\n") + extra.encode() + b"\n")
    with pytest.raises(ValueError):
        load_observations([path])


def test_bounds_files_and_total_input_rows(tmp_path, monkeypatch):
    path = write_export(tmp_path, [sample(), sample(1)])
    with pytest.raises(ValueError, match="between"):
        load_observations([])
    with pytest.raises(ValueError, match="between"):
        load_observations([path] * 101)
    monkeypatch.setattr(observations, "MAX_OBSERVATIONS", 1)
    with pytest.raises(ValueError, match="row count"):
        load_observations([path])
    monkeypatch.setattr(observations, "MAX_EXPORT_BYTES", 5)
    with pytest.raises(ValueError, match="file size"):
        load_observations([path])


def test_cli_writes_a_reproducible_report_and_never_overwrites(tmp_path, capsys):
    path = write_export(tmp_path, [sample()])
    output = tmp_path / "report.json"
    original_csv = path.read_bytes()
    args = [
        "--input",
        str(path),
        "--available-at-cutoff",
        CUTOFF.isoformat(),
        "--output",
        str(output),
    ]
    main(args)
    assert json.loads(capsys.readouterr().out) == json.loads(output.read_bytes())
    original_report = output.read_bytes()
    with pytest.raises(SystemExit) as error:
        main(args)
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert output.read_bytes() == original_report
    assert path.read_bytes() == original_csv


@pytest.mark.parametrize("cutoff", ["bad-date", "2026-10-03", "1791028800"])
def test_cli_rejects_bad_cutoffs_without_creating_output(tmp_path, capsys, cutoff):
    path = write_export(tmp_path, [sample()])
    output = tmp_path / "report.json"
    with pytest.raises(SystemExit) as error:
        main(["--input", str(path), "--available-at-cutoff", cutoff, "--output", str(output)])
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert not output.exists()


def test_cli_rejects_bad_csv_without_creating_output(tmp_path, capsys):
    path = write_export(tmp_path, [sample(data_origin="real")])
    output = tmp_path / "report.json"
    with pytest.raises(SystemExit) as error:
        main(
            [
                "--input",
                str(path),
                "--available-at-cutoff",
                CUTOFF.isoformat(),
                "--output",
                str(output),
            ]
        )
    assert error.value.code == 2
    assert "Traceback" not in capsys.readouterr().err
    assert not output.exists()
