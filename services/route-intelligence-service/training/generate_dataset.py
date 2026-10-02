import argparse
import json
from pathlib import Path

from app.routing.demo import load_demo_graph
from app.routing.graph import load_graph
from training.dataset import export_dataset
from training.synthetic import GeneratorConfig


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Generate timestamped synthetic segment datasets.")
    parser.add_argument(
        "--output", type=Path, required=True, help="New output directory; never overwritten."
    )
    parser.add_argument(
        "--graph", type=Path, help="Graph JSON; defaults to packaged synthetic-city-v1."
    )
    defaults = GeneratorConfig()
    parser.add_argument(
        "--start-at",
        default=defaults.start_at.isoformat(),
        help="First decision time with explicit offset.",
    )
    for name in (
        "seed",
        "train_days",
        "validation_days",
        "test_days",
        "interval_minutes",
        "recording_delay_seconds",
        "label_delay_seconds",
    ):
        parser.add_argument(
            f"--{name.replace('_', '-')}", type=int, default=getattr(defaults, name)
        )
    for name in ("noise_min", "noise_max"):
        parser.add_argument(
            f"--{name.replace('_', '-')}", type=float, default=getattr(defaults, name)
        )
    args = parser.parse_args(argv)
    options = vars(args).copy()
    output = options.pop("output")
    graph_path = options.pop("graph")
    try:
        config = GeneratorConfig.model_validate(options)
        graph = load_graph(graph_path) if graph_path is not None else load_demo_graph()
        manifest = export_dataset(graph, config, output)
    except ValueError as exc:
        parser.error(str(exc))
    print(
        json.dumps(
            {
                "output": str(output),
                "dataset_id": manifest["dataset_id"],
                "samples": manifest["files"]["samples.csv"]["rows"],
                "partitions": {name: data["rows"] for name, data in manifest["partitions"].items()},
                "samples_sha256": manifest["files"]["samples.csv"]["sha256"],
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
