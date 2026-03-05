from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from .models import AlertEvent, CheckResult, HealthLevel, StatusSnapshot


@dataclass
class _ComponentState:
    level: HealthLevel
    last_alert_at: datetime | None


class AlertingService:
    def __init__(self, cooldown_seconds: int, recovery_enabled: bool) -> None:
        self._cooldown = timedelta(seconds=max(1, cooldown_seconds))
        self._recovery_enabled = recovery_enabled
        self._states: dict[str, _ComponentState] = {}

    def evaluate(self, snapshot: StatusSnapshot) -> list[AlertEvent]:
        events: list[AlertEvent] = []
        now = snapshot.timestamp

        for check in snapshot.checks:
            previous = self._states.get(check.component)
            event, state = self._evaluate_component(previous, check, now)
            self._states[check.component] = state
            if event is not None:
                events.append(event)

        return events

    def _evaluate_component(
        self,
        previous: _ComponentState | None,
        check: CheckResult,
        now: datetime,
    ) -> tuple[AlertEvent | None, _ComponentState]:
        if check.level == HealthLevel.OK:
            if previous and previous.level != HealthLevel.OK and self._recovery_enabled:
                event = AlertEvent(
                    component=check.component,
                    level=check.level,
                    message=f"RECOVERY {check.component} is OK. {check.summary}",
                )
                return event, _ComponentState(HealthLevel.OK, previous.last_alert_at)
            return None, _ComponentState(HealthLevel.OK, previous.last_alert_at if previous else None)

        should_send = (
            previous is None
            or previous.level == HealthLevel.OK
            or previous.level != check.level
        )

        if not should_send and previous and previous.last_alert_at:
            should_send = (now - previous.last_alert_at) >= self._cooldown

        if should_send:
            event = AlertEvent(
                component=check.component,
                level=check.level,
                message=f"ALERT {check.component} is {check.level.name}. {check.summary} | {check.details}",
            )
            return event, _ComponentState(check.level, now)

        return None, _ComponentState(check.level, previous.last_alert_at if previous else now)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)
