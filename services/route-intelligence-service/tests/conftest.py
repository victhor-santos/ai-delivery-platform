import pytest


@pytest.fixture(scope="session")
def model_bundle(tmp_path_factory):
    from app.routing.demo import load_demo_graph
    from training.dataset import export_dataset
    from training.load_dataset import load_dataset
    from training.synthetic import GeneratorConfig
    from training.train import train_dataset

    root = tmp_path_factory.mktemp("segment-model")
    dataset = root / "dataset"
    artifact = root / "artifact"
    export_dataset(
        load_demo_graph(),
        GeneratorConfig(train_days=7, validation_days=7, test_days=7, interval_minutes=360),
        dataset,
    )
    train_dataset(load_dataset(dataset), artifact)
    return artifact, dataset


@pytest.fixture
def graph_data():
    return {
        "graph_version": "test-v1",
        "timezone": "America/Sao_Paulo",
        "vehicle_profile": "motorcycle",
        "data_origin": "synthetic",
        "nodes": [
            {"node_id": "A", "lat": -23.5505, "lon": -46.6333},
            {"node_id": "B", "lat": -23.5540, "lon": -46.6400},
            {"node_id": "C", "lat": -23.5610, "lon": -46.6560},
        ],
        "segments": [
            {
                "segment_id": "A-B",
                "from_node": "A",
                "to_node": "B",
                "distance_km": 0.9,
                "road_type": "residential",
                "reference_speed_kmh": 15,
            },
            {
                "segment_id": "B-C",
                "from_node": "B",
                "to_node": "C",
                "distance_km": 2.0,
                "road_type": "primary",
                "reference_speed_kmh": 30,
            },
        ],
    }


@pytest.fixture(scope="session")
def observation_training_dataset(tmp_path_factory):
    """Small simulated journeys spanning three periods on the checked-in demo graph."""
    import csv
    from datetime import UTC, datetime, timedelta
    from uuid import UUID

    from app.ml.features import departure_context
    from app.routing.demo import load_demo_graph
    from training.observation_dataset import (
        export_observation_dataset,
        load_observation_dataset,
    )
    from training.observations import OBSERVATION_COLUMNS, DeliveryObservation, load_observations
    from training.splits import SplitPlan

    root = tmp_path_factory.mktemp("observation-training")
    graph = load_demo_graph()
    plan = SplitPlan(
        start_at=datetime(2026, 9, 1, tzinfo=UTC),
        train_end=datetime(2026, 9, 2, tzinfo=UTC),
        validation_end=datetime(2026, 9, 3, tzinfo=UTC),
        test_end=datetime(2026, 9, 4, tzinfo=UTC),
    )
    rows = []
    for period, start in enumerate((plan.start_at, plan.train_end, plan.validation_end)):
        for index in range(24):
            number = period * 24 + index + 1
            segment = graph.segments[index % len(graph.segments)]
            prediction = start + timedelta(minutes=index * 45)
            hour, weekday = departure_context(prediction)
            reference = segment.distance_km / segment.reference_speed_kmh * 60
            actual = round(reference * (1 + 0.08 * ((index * 7) % 5)), 4)
            entered = prediction + timedelta(minutes=1)
            exited = entered + timedelta(minutes=actual)
            recorded = exited + timedelta(seconds=2)
            rows.append(
                DeliveryObservation(
                    **segment.model_dump(),
                    schema_version="delivery-segment-observation-v1",
                    traversal_id=UUID(int=number * 1000),
                    delivery_id=UUID(int=number),
                    route_plan_id=UUID(int=number * 100),
                    sequence=0,
                    data_origin="simulated",
                    prediction_data_origin="synthetic",
                    graph_version=graph.graph_version,
                    model_version=f"historical-model-{index % 2}",
                    feature_schema_version="segment-features-v1",
                    traffic_level=("low", "medium", "high")[index % 3],
                    hour=hour,
                    day_of_week=weekday,
                    timezone=graph.timezone,
                    traffic_source="synthetic-traffic-v1",
                    traffic_observed_at=prediction - timedelta(minutes=1),
                    traffic_available_at=prediction,
                    features_available_at=prediction,
                    prediction_at=prediction,
                    planned_departure_at=prediction,
                    context_as_of=prediction,
                    predicted_travel_time_minutes=actual + 1 + index % 2,
                    entered_at=entered,
                    entry_recorded_at=entered + timedelta(seconds=1),
                    exited_at=exited,
                    recorded_at=recorded,
                    label_available_at=recorded,
                    actual_travel_time_minutes=actual,
                )
            )
    source = root / "observations.csv"
    with source.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=OBSERVATION_COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(row.model_dump(mode="json") for row in rows)
    dataset = root / "dataset"
    export_observation_dataset(load_observations([source]), plan, dataset)
    return load_observation_dataset(dataset), dataset, graph


@pytest.fixture(scope="session")
def observation_model_bundle(tmp_path_factory, observation_training_dataset):
    from training.train_observations import train_observation_dataset

    data, _, graph = observation_training_dataset
    artifact = tmp_path_factory.mktemp("observation-model") / "artifact"
    train_observation_dataset(data, artifact, graph)
    return artifact, data, graph
