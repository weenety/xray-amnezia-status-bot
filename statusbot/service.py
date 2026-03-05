from __future__ import annotations

import asyncio
from datetime import datetime

from .checks import NetworkHealthChecker, SystemHealthChecker, XrayHealthChecker
from .config import Settings
from .models import CheckResult, HealthLevel, StatusSnapshot


class MonitoringService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.network = NetworkHealthChecker(settings)
        self.xray = XrayHealthChecker(settings)
        self.system = SystemHealthChecker(settings)
        self._latest_snapshot: StatusSnapshot | None = None

    async def collect_status(self) -> StatusSnapshot:
        snapshot = await asyncio.to_thread(self._collect_status_sync)
        self._latest_snapshot = snapshot
        return snapshot

    async def get_latest_or_collect(self) -> StatusSnapshot:
        if self._latest_snapshot is not None:
            return self._latest_snapshot
        return await self.collect_status()

    def _collect_status_sync(self) -> StatusSnapshot:
        checks: list[CheckResult] = []
        checks.append(self.network.check())
        checks.append(self.xray.check())
        checks.extend(self.system.check())

        overall = max((c.level for c in checks), default=HealthLevel.WARN)
        return StatusSnapshot(timestamp=datetime.now().astimezone(), overall=overall, checks=checks)
