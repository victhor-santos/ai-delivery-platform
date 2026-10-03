import json
from datetime import UTC, datetime

import pytest

from app.routing.demo import load_demo_graph
from app.routing.traffic import (
    MAX_TRAFFIC_BYTES,
    SyntheticTrafficScenario,
    TrafficConfigurationError,
    load_demo_traffic,
    load_traffic,
    traffic_snapshot,
)

NOW = datetime(2026, 10, 2, 22, tzinfo=UTC)


def scenario(graph):
    return {
        "scenario_version": "test-traffic-v1",
        "graph_version": graph.graph_version,
        "data_origin": "synthetic",
        "levels": {segment.segment_id: "low" for segment in graph.segments},
    }


def test_packaged_snapshot_covers_graph_and_is_immutable():
    graph = load_demo_graph()
    snapshot = load_demo_traffic(graph, NOW)
    assert snapshot.levels.keys() == graph.segments_by_id.keys()
    assert snapshot.levels["A-D"] == "high"
    assert snapshot.levels["A-B"] == "low"
    assert snapshot.available_at == snapshot.observed_at == NOW
    with pytest.raises(TypeError):
        snapshot.levels["A-D"] = "low"


def test_snapshot_copies_configuration_without_following_later_mutations():
    graph = load_demo_graph()
    configuration = SyntheticTrafficScenario.model_validate(scenario(graph))
    snapshot = traffic_snapshot(configuration, graph, NOW)
    configuration.levels["A-D"] = "high"
    assert snapshot.levels["A-D"] == "low"


@pytest.mark.parametrize("mutation", ["missing", "extra", "graph", "category", "extra_field"])
def test_incompatible_traffic_is_rejected(tmp_path, mutation):
    graph = load_demo_graph()
    data = scenario(graph)
    if mutation == "missing":
        data["levels"].pop("A-B")
    elif mutation == "extra":
        data["levels"]["unknown"] = "low"
    elif mutation == "graph":
        data["graph_version"] = "another-v1"
    elif mutation == "category":
        data["levels"]["A-B"] = "gridlock"
    else:
        data["actual_travel_time_minutes"] = 1
    path = tmp_path / "traffic.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(TrafficConfigurationError):
        load_traffic(path, graph, NOW)


def test_missing_and_oversized_traffic_files_have_controlled_errors(tmp_path):
    graph = load_demo_graph()
    with pytest.raises(TrafficConfigurationError):
        load_traffic(tmp_path / "missing.json", graph, NOW)
    path = tmp_path / "oversized.json"
    path.write_bytes(b" " * (MAX_TRAFFIC_BYTES + 1))
    with pytest.raises(TrafficConfigurationError):
        load_traffic(path, graph, NOW)


def test_naive_snapshot_timestamp_is_rejected():
    graph = load_demo_graph()
    with pytest.raises(TrafficConfigurationError):
        traffic_snapshot(
            SyntheticTrafficScenario.model_validate(scenario(graph)), graph, datetime(2026, 10, 2)
        )
