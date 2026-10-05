from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta
from types import MappingProxyType
from typing import Self

from pydantic import BaseModel, ConfigDict, model_validator

from training.schema import SegmentSample, UtcTimestamp
from training.synthetic import GeneratorConfig


class SplitPlan(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    start_at: UtcTimestamp
    train_end: UtcTimestamp
    validation_end: UtcTimestamp
    test_end: UtcTimestamp

    @model_validator(mode="after")
    def validate_boundaries(self) -> Self:
        if not self.start_at < self.train_end < self.validation_end < self.test_end:
            raise ValueError("Split boundaries must be strictly increasing.")
        return self

    def partition_at(self, prediction_at: datetime) -> str:
        if self.start_at <= prediction_at < self.train_end:
            return "train"
        if self.train_end <= prediction_at < self.validation_end:
            return "validation"
        if self.validation_end <= prediction_at < self.test_end:
            return "test"
        return "outside"

    def cutoff_for(self, partition: str) -> datetime:
        return getattr(
            self,
            {"train": "train_end", "validation": "validation_end", "test": "test_end"}[partition],
        )


def split_plan_for(config: GeneratorConfig) -> SplitPlan:
    train_end = config.start_at + timedelta(days=config.train_days)
    validation_end = train_end + timedelta(days=config.validation_days)
    return SplitPlan(
        start_at=config.start_at,
        train_end=train_end,
        validation_end=validation_end,
        test_end=validation_end + timedelta(days=config.test_days),
    )


@dataclass(frozen=True)
class DatasetSplit[Sample]:
    plan: SplitPlan
    train: tuple[Sample, ...]
    validation: tuple[Sample, ...]
    test: tuple[Sample, ...]
    excluded: tuple[Sample, ...]
    excluded_reasons: Mapping[str, str]

    def subsets(self) -> dict[str, tuple[Sample, ...]]:
        return {
            "train": self.train,
            "validation": self.validation,
            "test": self.test,
            "excluded": self.excluded,
        }


def split_samples(samples: Sequence[SegmentSample], plan: SplitPlan) -> DatasetSplit[SegmentSample]:
    if not samples:
        raise ValueError("Cannot split an empty dataset.")
    identifiers = {sample.traversal_id for sample in samples}
    if len(identifiers) != len(samples):
        raise ValueError("Traversal identifiers must be unique across the dataset.")
    if len({sample.graph_version for sample in samples}) != 1:
        raise ValueError("A dataset must use one graph version.")
    groups: dict[str, list[SegmentSample]] = defaultdict(list)
    for sample in samples:
        groups[sample.scenario_id].append(sample)
    partitions: dict[str, list[SegmentSample]] = {
        "train": [],
        "validation": [],
        "test": [],
        "excluded": [],
    }
    reasons = {}
    for scenario_id in sorted(groups):
        group = sorted(
            groups[scenario_id], key=lambda sample: (sample.prediction_at, sample.traversal_id)
        )
        memberships = {plan.partition_at(sample.prediction_at) for sample in group}
        if memberships == {"outside"}:
            reason = "outside_period"
        elif len(memberships) != 1:
            reason = "crosses_time_boundary"
        else:
            partition = memberships.pop()
            cutoff = plan.cutoff_for(partition)
            reason = (
                "label_unavailable_at_cutoff"
                if any(sample.label_available_at > cutoff for sample in group)
                else None
            )
        if reason is not None:
            reasons[scenario_id] = reason
            partitions["excluded"].extend(group)
        else:
            partitions[partition].extend(group)
    return DatasetSplit(
        plan=plan,
        **{name: tuple(rows) for name, rows in partitions.items()},
        excluded_reasons=MappingProxyType(reasons),
    )
