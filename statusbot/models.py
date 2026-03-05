from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import IntEnum


class HealthLevel(IntEnum):
    OK = 0
    WARN = 1
    CRIT = 2


@dataclass(frozen=True)
class CheckResult:
    component: str
    level: HealthLevel
    summary: str
    details: str


@dataclass(frozen=True)
class StatusSnapshot:
    timestamp: datetime
    overall: HealthLevel
    checks: list[CheckResult]


@dataclass(frozen=True)
class AlertEvent:
    component: str
    level: HealthLevel
    message: str
