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
