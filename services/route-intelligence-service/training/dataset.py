import csv
import hashlib
import json
import platform
from collections import Counter, defaultdict
from collections.abc import Sequence
from importlib.metadata import version
from pathlib import Path
from statistics import fmean

from app.ml.features import (
    FEATURE_COLUMNS,
    FEATURE_SCHEMA_VERSION,
    ROAD_TYPES,
    TRAFFIC_LEVELS,
    SegmentFeatures,
)
from app.routing.graph import RoadGraph
from app.routing.provenance import graph_checksum
from training.schema import (
    GENERATOR_VERSION,
    SAMPLE_COLUMNS,
    SAMPLE_SCHEMA_VERSION,
    TARGET_COLUMN,
    SegmentSample,
)
from training.serialization import json_bytes
from training.splits import DatasetSplit, split_plan_for, split_samples
from training.synthetic import (
    NIGHT_FACTOR,
    PEAK_FACTOR,
    PEAK_HOURS,
    TRAFFIC_FACTORS,
    WEEKEND_FACTOR,
    GeneratorConfig,
    generate_samples,
)


def _write_csv(path: Path, samples: Sequence[SegmentSample]) -> dict[str, object]:
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=SAMPLE_COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(sample.model_dump(mode="json") for sample in samples)
    with path.open("rb") as source:
        checksum = hashlib.file_digest(source, "sha256").hexdigest()
    return {"rows": len(samples), "sha256": checksum, "bytes": path.stat().st_size}


def _partition_summary(samples: Sequence[SegmentSample]) -> dict[str, object]:
    timestamps = {
        "first_prediction_at": min((s.prediction_at for s in samples), default=None),
        "last_prediction_at": max((s.prediction_at for s in samples), default=None),
        "last_label_available_at": max((s.label_available_at for s in samples), default=None),
    }
    return {
        "rows": len(samples),
        "scenarios": len({sample.scenario_id for sample in samples}),
    } | {key: value.isoformat() if value is not None else None for key, value in timestamps.items()}


def _overlap_audit(split: DatasetSplit) -> dict[str, int]:
    signatures: dict[tuple, set[str]] = defaultdict(set)
    for name in ("train", "validation", "test"):
        for sample in getattr(split, name):
            record = sample.model_dump()
            signature = tuple(record[column] for column in (*FEATURE_COLUMNS, TARGET_COLUMN))
            signatures[signature].add(name)
    return {
        "feature_target_signatures_in_multiple_partitions": sum(
            len(names) > 1 for names in signatures.values()
        )
    }


def export_dataset(graph: RoadGraph, config: GeneratorConfig, output: Path) -> dict[str, object]:
    if output.exists():
        raise ValueError("Output already exists; choose a new directory to preserve the dataset.")
    samples = generate_samples(graph, config)
    split = split_samples(samples, split_plan_for(config))
    if not split.train or not split.validation or not split.test:
        raise ValueError(
            "All three partitions must contain eligible scenarios; review periods and delays."
        )
    subsets = split.subsets()
    files = {}
    try:
        output.mkdir(parents=True, exist_ok=False)
        for name, rows in {"samples": samples, **subsets}.items():
            files[f"{name}.csv"] = _write_csv(output / f"{name}.csv", rows)
        graph_sha256 = graph_checksum(graph)
        configuration = config.model_dump(mode="json")
        identity = {"graph_sha256": graph_sha256, "configuration": configuration, "files": files}
        targets = [sample.actual_travel_time_minutes for sample in samples]
        manifest = {
            "manifest_version": "segment-dataset-manifest-v1",
            "dataset_id": hashlib.sha256(json_bytes(identity)).hexdigest(),
            "data_origin": "synthetic",
            "generator_version": GENERATOR_VERSION,
            "graph": {
                "graph_version": graph.graph_version,
                "sha256": graph_sha256,
                "timezone": graph.timezone,
                "vehicle_profile": graph.vehicle_profile,
                "nodes": len(graph.nodes),
                "segments": len(graph.segments),
            },
            "configuration": configuration,
            "schema": {
                "sample_version": SAMPLE_SCHEMA_VERSION,
                "columns": SAMPLE_COLUMNS,
                "feature_version": FEATURE_SCHEMA_VERSION,
                "features": FEATURE_COLUMNS,
                "target": TARGET_COLUMN,
                "road_types": ROAD_TYPES,
                "traffic_levels": TRAFFIC_LEVELS,
                "features_json_schema": SegmentFeatures.model_json_schema(),
                "sample_json_schema": SegmentSample.model_json_schema(),
            },
            "generation_parameters": {
                "formula": (
                    "60 * distance_km / reference_speed_kmh * traffic_factor * time_factor * noise"
                ),
                "traffic_factors": dict(TRAFFIC_FACTORS),
                "traffic_sampling": "uniform_per_segment",
                "weekday_peak_hours": PEAK_HOURS,
                "weekday_peak_factor": PEAK_FACTOR,
                "night_hours": list(range(6)),
                "night_factor": NIGHT_FACTOR,
                "weekend_factor": WEEKEND_FACTOR,
                "other_time_factor": 1.0,
                "noise_distribution": "uniform",
                "noise_min": config.noise_min,
                "noise_max": config.noise_max,
            },
            "environment": {
                "python": platform.python_version(),
                "implementation": platform.python_implementation(),
                "service": version("route-intelligence-service"),
                "pydantic": version("pydantic"),
                "timezone_source": "tzdata_package",
                "tzdata": version("tzdata"),
            },
            "split_policy": {
                "time_basis": "prediction_at",
                "group_by": "scenario_id",
                "intervals": "start_inclusive_end_exclusive",
                "label_availability": "at_or_before_partition_end",
                "ineligible_group": "exclude_entire_scenario",
                "boundaries": split.plan.model_dump(mode="json"),
            },
            "partitions": {name: _partition_summary(rows) for name, rows in subsets.items()},
            "excluded_scenarios": dict(split.excluded_reasons),
            "audit": _overlap_audit(split),
            "statistics": {
                "target_minutes": {
                    "min": min(targets),
                    "max": max(targets),
                    "mean": fmean(targets),
                },
                "traffic_counts": dict(Counter(s.traffic_level for s in samples)),
                "road_type_counts": dict(Counter(s.road_type for s in samples)),
                "hour_counts": dict(Counter(str(s.hour) for s in samples)),
                "weekday_counts": dict(Counter(str(s.day_of_week) for s in samples)),
            },
            "files": files,
        }
        manifest_bytes = json_bytes(manifest)
        (output / "manifest.json").write_bytes(manifest_bytes)
        return json.loads(manifest_bytes)
    except OSError as exc:
        raise ValueError(
            "Unable to export the dataset; an incomplete directory has no valid manifest."
        ) from exc
