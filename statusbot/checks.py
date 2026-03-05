from __future__ import annotations

import os
import re
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
        warnings: list[str] = []

        dns_ok = self._check_dns(errors)
        http_ok, http_total, http_failures = self._check_http()
        tcp_ok = self._check_tcp(errors)
        ping = self._check_ping()
        gateway, default_iface = self._default_route()

        if http_ok == 0:
            errors.extend(http_failures)
        elif http_failures:
            warnings.extend(http_failures)

        if ping.error:
            warnings.append(f"ping({self.settings.network_ping_host}): {ping.error}")
        else:
            if ping.loss_percent is not None:
                if ping.loss_percent >= self.settings.network_ping_crit_loss_percent:
                    errors.append(f"ping loss {ping.loss_percent:.1f}%")
                elif ping.loss_percent >= self.settings.network_ping_warn_loss_percent:
                    warnings.append(f"ping loss {ping.loss_percent:.1f}%")
            if (
                ping.avg_ms is not None
                and ping.avg_ms >= self.settings.network_ping_warn_avg_ms
            ):
                warnings.append(f"ping avg {ping.avg_ms:.1f}ms")

        iface_stats = self._read_interface_stats(default_iface) if default_iface else None

        if errors:
            level = HealthLevel.CRIT
        elif warnings:
            level = HealthLevel.WARN
        else:
            level = HealthLevel.OK
        summary = (
            f"dns={'ok' if dns_ok else 'fail'}, "
            f"http={http_ok}/{http_total}, "
            f"tcp={'ok' if tcp_ok else 'fail'}"
        )
        if ping.loss_percent is not None:
            summary += f", pingLoss={ping.loss_percent:.1f}%"

        details_parts = [
            f"defaultIface={default_iface or 'unknown'}",
            f"defaultGw={gateway or 'unknown'}",
        ]
        if ping.avg_ms is not None:
            details_parts.append(f"pingAvg={ping.avg_ms:.1f}ms")
        if iface_stats is not None:
            details_parts.append(
                "ifaceErrors="
                f"rx_err {iface_stats['rx_errors']}, "
                f"tx_err {iface_stats['tx_errors']}, "
                f"rx_drop {iface_stats['rx_dropped']}, "
                f"tx_drop {iface_stats['tx_dropped']}"
            )
        if warnings:
            details_parts.append(f"warn={' | '.join(warnings)}")
        if errors:
            details_parts.append(f"errors={' | '.join(errors)}")

        details = ", ".join(details_parts)
        return CheckResult("Network", level, summary, details)

    def _check_dns(self, errors: list[str]) -> bool:
        try:
            socket.getaddrinfo(self.settings.network_dns_host, None)
            return True
        except Exception as ex:
            errors.append(f"dns({self.settings.network_dns_host}): {ex}")
            return False

    def _check_http(self) -> tuple[int, int, list[str]]:
        ok = 0
        failures: list[str] = []
        timeout = self.settings.network_http_timeout_seconds
        urls = self.settings.network_urls
        for url in urls:
            try:
                req = urllib.request.Request(url, method="GET")
                with urllib.request.urlopen(req, timeout=timeout) as response:
                    status = response.getcode()
                    if 200 <= status < 400:
                        ok += 1
                    else:
                        failures.append(f"http({url}) status={status}")
            except Exception as ex:
                failures.append(f"http({url}): {ex}")
        return ok, len(urls), failures

    def _check_tcp(self, errors: list[str]) -> bool:
        host = self.settings.network_tcp_host
        port = self.settings.network_tcp_port
        timeout = self.settings.network_http_timeout_seconds
        try:
            with socket.create_connection((host, port), timeout=timeout):
                return True
        except OSError as ex:
            errors.append(f"tcp({host}:{port}): {ex}")
            return False

    def _check_ping(self) -> "PingProbeResult":
        ping_path = shutil.which("ping")
        if ping_path is None:
            return PingProbeResult(error="ping not installed")

        cmd = [
            ping_path,
            "-c",
            str(self.settings.network_ping_count),
            "-W",
            str(self.settings.network_ping_timeout_seconds),
            self.settings.network_ping_host,
        ]

        try:
            timeout = max(
                3,
                self.settings.network_ping_count
                * self.settings.network_ping_timeout_seconds
                + 2,
            )
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
            output = f"{result.stdout}\n{result.stderr}".strip()
            loss, avg = self._parse_ping_output(output)
            if result.returncode != 0 and loss is None:
                return PingProbeResult(error="ping command failed")
            return PingProbeResult(loss_percent=loss, avg_ms=avg)
        except subprocess.TimeoutExpired:
            return PingProbeResult(error="ping timeout")
        except OSError as ex:
            return PingProbeResult(error=str(ex))

    def _parse_ping_output(self, output: str) -> tuple[float | None, float | None]:
        loss_match = re.search(r"([\d.]+)%\s+packet loss", output)
        loss = float(loss_match.group(1)) if loss_match else None

        avg = None
        avg_match = re.search(r"=\s*[\d.]+/([\d.]+)/[\d.]+/[\d.]+", output)
        if avg_match:
            avg = float(avg_match.group(1))
        else:
            short_avg_match = re.search(r"=\s*[\d.]+/([\d.]+)/[\d.]+", output)
            if short_avg_match:
                avg = float(short_avg_match.group(1))

        return loss, avg

    def _default_route(self) -> tuple[str | None, str | None]:
        try:
            result = subprocess.run(
                ["ip", "route", "show", "default"],
                capture_output=True,
                text=True,
                timeout=2,
                check=False,
            )
        except OSError:
            return None, None

        line = result.stdout.strip().splitlines()
        if not line:
            return None, None

        match = re.search(r"default via (\S+) dev (\S+)", line[0])
        if not match:
            return None, None
        return match.group(1), match.group(2)

    def _read_interface_stats(self, iface: str) -> dict[str, int] | None:
        stats_path = f"/sys/class/net/{iface}/statistics"
        if not os.path.isdir(stats_path):
            return None
        result: dict[str, int] = {}
        fields = ("rx_errors", "tx_errors", "rx_dropped", "tx_dropped")
        for field in fields:
            try:
                with open(f"{stats_path}/{field}", "r", encoding="utf-8") as file:
                    result[field] = int(file.read().strip())
            except (OSError, ValueError):
                return None
        return result


@dataclass(frozen=True)
class PingProbeResult:
    loss_percent: float | None = None
    avg_ms: float | None = None
    error: str | None = None


@dataclass
class XrayHealthChecker:
    settings: Settings

    def check(self) -> CheckResult:
        timeout = self.settings.xray_command_timeout_seconds
        service_name = self.settings.xray_service_name

        status = self._run(["systemctl", "is-active", service_name], timeout)
        if status["timed_out"]:
            return CheckResult(
                "Xray",
                HealthLevel.CRIT,
                "status timeout",
                "systemctl is-active timed out",
            )

        output = status["output"].strip()
        active = status["code"] == 0 and output == "active"
        if not active:
            details = output or "inactive"
            return CheckResult("Xray", HealthLevel.CRIT, "inactive", details)

        meta = self._run(
            [
                "systemctl",
                "show",
                service_name,
                "--property",
                "SubState,ActiveEnterTimestamp,NRestarts",
                "--value",
            ],
            timeout,
        )
        if meta["code"] != 0:
            return CheckResult("Xray", HealthLevel.OK, "active", "active")

        lines = [line.strip() for line in str(meta["output"]).splitlines()]
        sub_state = lines[0] if len(lines) > 0 and lines[0] else "active"
        active_since = lines[1] if len(lines) > 1 and lines[1] else "unknown"
        restart_count = self._parse_int(lines[2]) if len(lines) > 2 else 0

        if restart_count >= self.settings.xray_restart_warn_count > 0:
            return CheckResult(
                "Xray",
                HealthLevel.WARN,
                f"active, restarts={restart_count}",
                f"subState={sub_state}; since={active_since}",
            )

        return CheckResult(
            "Xray",
            HealthLevel.OK,
            "active",
            f"subState={sub_state}; since={active_since}; restarts={restart_count}",
        )

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

    def _parse_int(self, value: str) -> int:
        try:
            return int(value)
        except ValueError:
            return 0


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
            CheckResult(
                "CPU",
                cpu_level,
                self._fmt_percent(cpu),
                f"cpuLoad={self._fmt_percent(cpu)}",
            ),
            CheckResult(
                "RAM",
                ram_level,
                self._fmt_percent(ram),
                f"used={self._fmt_percent(ram)}",
            ),
            CheckResult(
                "Disk",
                disk_level,
                self._fmt_percent(disk),
                f"rootUsed={self._fmt_percent(disk)}",
            ),
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
