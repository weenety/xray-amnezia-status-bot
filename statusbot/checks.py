from __future__ import annotations

import os
import shutil
import socket
import subprocess
import time
import urllib.request
from dataclasses import dataclass

from .config import Settings
from .models import CheckResult, HealthLevel


class ThresholdEvaluator:
    @staticmethod
    def classify(value_percent: float, critical_percent: float) -> HealthLevel:
        if value_percent < 0:
            return HealthLevel.WARN
        if value_percent >= critical_percent:
            return HealthLevel.CRIT
        if value_percent >= critical_percent * 0.9:
            return HealthLevel.WARN
        return HealthLevel.OK


@dataclass
class NetworkHealthChecker:
    settings: Settings

    def check(self) -> CheckResult:
        errors: list[str] = []

        dns_ok = self._check_dns(errors)
        http_ok = self._check_http(errors)
        iface_count = self._count_active_non_loopback_interfaces()

        level = HealthLevel.OK if not errors else HealthLevel.CRIT
        summary = f"dns={'ok' if dns_ok else 'fail'}, http={http_ok}/{len(self.settings.network_urls)}"
        details = f"activeIfaces={iface_count}"
        if errors:
            details += f", errors={' | '.join(errors)}"

        return CheckResult("Network", level, summary, details)

    def _check_dns(self, errors: list[str]) -> bool:
        try:
            socket.getaddrinfo(self.settings.network_dns_host, None)
            return True
        except Exception as ex:
            errors.append(f"dns({self.settings.network_dns_host}): {ex}")
            return False

    def _check_http(self, errors: list[str]) -> int:
        ok = 0
        timeout = self.settings.network_http_timeout_seconds
        for url in self.settings.network_urls:
            try:
                req = urllib.request.Request(url, method="GET")
                with urllib.request.urlopen(req, timeout=timeout) as response:
                    status = response.getcode()
                    if 200 <= status < 400:
                        ok += 1
                    else:
                        errors.append(f"http({url}) status={status}")
            except Exception as ex:
                errors.append(f"http({url}): {ex}")
        return ok

    def _count_active_non_loopback_interfaces(self) -> int:
        net_path = "/sys/class/net"
        if not os.path.isdir(net_path):
            return -1

        count = 0
        for iface in os.listdir(net_path):
            if iface == "lo":
                continue
            state_path = os.path.join(net_path, iface, "operstate")
            try:
                with open(state_path, "r", encoding="utf-8") as f:
                    state = f.read().strip().lower()
                if state == "up":
                    count += 1
            except OSError:
                continue
        return count


@dataclass
class XrayHealthChecker:
    settings: Settings

    def check(self) -> CheckResult:
        timeout = self.settings.xray_command_timeout_seconds
        service_name = self.settings.xray_service_name

        status = self._run(["systemctl", "is-active", service_name], timeout)
        if status["timed_out"]:
            return CheckResult("Xray", HealthLevel.CRIT, "status timeout", "systemctl is-active timed out")

        output = status["output"].strip()
        active = status["code"] == 0 and output == "active"
        if not active:
            details = output or "inactive"
            return CheckResult("Xray", HealthLevel.CRIT, "inactive", details)

        meta = self._run(
            ["systemctl", "show", service_name, "--property", "SubState,ActiveEnterTimestamp", "--value"],
            timeout,
        )
        details = meta["output"].replace("\n", ";").strip() if meta["code"] == 0 else "active"
        details = details or "active"
        return CheckResult("Xray", HealthLevel.OK, "active", details)

    def _run(self, command: list[str], timeout_seconds: int) -> dict[str, object]:
        try:
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                timeout=timeout_seconds,
                check=False,
            )
            output = (result.stdout or "") + (result.stderr or "")
            return {"code": result.returncode, "output": output.strip(), "timed_out": False}
        except subprocess.TimeoutExpired:
            return {"code": -1, "output": "timeout", "timed_out": True}
        except Exception as ex:
            return {"code": -1, "output": str(ex), "timed_out": False}


@dataclass
class SystemHealthChecker:
    settings: Settings

    def check(self) -> list[CheckResult]:
        cpu = self._cpu_percent()
        ram = self._ram_percent()
        disk = self._disk_percent("/")

        cpu_level = ThresholdEvaluator.classify(cpu, self.settings.threshold_cpu_percent)
        ram_level = ThresholdEvaluator.classify(ram, self.settings.threshold_ram_percent)
        disk_level = ThresholdEvaluator.classify(disk, self.settings.threshold_disk_percent)

        return [
            CheckResult("CPU", cpu_level, self._fmt_percent(cpu), f"cpuLoad={self._fmt_percent(cpu)}"),
            CheckResult("RAM", ram_level, self._fmt_percent(ram), f"used={self._fmt_percent(ram)}"),
            CheckResult("Disk", disk_level, self._fmt_percent(disk), f"rootUsed={self._fmt_percent(disk)}"),
        ]

    def _cpu_percent(self) -> float:
        try:
            idle_1, total_1 = self._read_proc_stat()
            time.sleep(0.2)
            idle_2, total_2 = self._read_proc_stat()
            total_delta = total_2 - total_1
            idle_delta = idle_2 - idle_1
            if total_delta <= 0:
                return -1
            return max(0.0, min(100.0, (1.0 - (idle_delta / total_delta)) * 100.0))
        except Exception:
            return -1

    def _read_proc_stat(self) -> tuple[float, float]:
        with open("/proc/stat", "r", encoding="utf-8") as f:
            line = f.readline().strip()
        parts = line.split()
        values = [float(x) for x in parts[1:]]
        idle = values[3] + (values[4] if len(values) > 4 else 0.0)
        total = sum(values)
        return idle, total

    def _ram_percent(self) -> float:
        try:
            mem_total = 0.0
            mem_available = 0.0
            with open("/proc/meminfo", "r", encoding="utf-8") as f:
                for line in f:
                    if line.startswith("MemTotal:"):
                        mem_total = float(line.split()[1])
                    elif line.startswith("MemAvailable:"):
                        mem_available = float(line.split()[1])
            if mem_total <= 0:
                return -1
            used = mem_total - mem_available
            return max(0.0, min(100.0, (used / mem_total) * 100.0))
        except Exception:
            return -1

    def _disk_percent(self, path: str) -> float:
        try:
            usage = shutil.disk_usage(path)
            if usage.total <= 0:
                return -1
            used = usage.total - usage.free
            return max(0.0, min(100.0, (used / usage.total) * 100.0))
        except Exception:
            return -1

    def _fmt_percent(self, value: float) -> str:
        if value < 0:
            return "unknown"
        return f"{value:.1f}%"
