from importlib.resources import as_file, files

from app.routing.graph import RoadGraph, load_graph


def load_demo_graph() -> RoadGraph:
    resource = files("app.routing").joinpath("data", "synthetic-city-v1.json")
    with as_file(resource) as path:
        return load_graph(path)
