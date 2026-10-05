"""Offline CLI for preparing Delivery exports without changing the synthetic dataset."""

import argparse
import json
from pathlib import Path

from pydantic import TypeAdapter

from training.observation_dataset import export_observation_dataset
from training.observations import ObservationTimestamp, load_observations
from training.splits import SplitPlan


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Prepare Delivery observations by delivery and time."
    )
    parser.add_argument(
        "--input", type=Path, nargs="+", required=True, help="Delivery CSV exports."
    )
    for boundary in ("start-at", "train-end", "validation-end", "test-end"):
        parser.add_argument(
            f"--{boundary}", required=True, help="Explicit ISO timestamp with timezone."
        )
    parser.add_argument(
        "--output", type=Path, required=True, help="New observation dataset directory."
    )
    args = parser.parse_args(argv)
    try:
        timestamp = TypeAdapter(ObservationTimestamp)
        plan = SplitPlan.model_validate(
            {
                boundary: timestamp.validate_python(getattr(args, boundary))
                for boundary in ("start_at", "train_end", "validation_end", "test_end")
            }
        )
        manifest = export_observation_dataset(load_observations(args.input), plan, args.output)
    except (ValueError, OSError, OverflowError) as exc:
        parser.error(str(exc))
    print(
        json.dumps(
            {
                "output": str(args.output),
                "dataset_id": manifest["dataset_id"],
                "data_origin": manifest["data_origin"],
                "partitions": manifest["partitions"],
                "audit": manifest["audit"],
            },
            indent=2,
            allow_nan=False,
        )
    )


if __name__ == "__main__":
    main()
