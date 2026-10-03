import hashlib
import json

from app.routing.graph import RoadGraph


def graph_checksum(graph: RoadGraph) -> str:
    data = graph.model_dump(mode="json")
    data["nodes"] = sorted(data["nodes"], key=lambda node: node["node_id"])
    data["segments"] = sorted(data["segments"], key=lambda segment: segment["segment_id"])
    content = (json.dumps(data, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")
    return hashlib.sha256(content).hexdigest()
