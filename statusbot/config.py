from __future__ import annotations

import os
from dataclasses import dataclass


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _env_csv(name: str, default: list[str]) -> list[str]:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return [x.strip() for x in raw.split(",") if x.strip()]


@dataclass(frozen=True)
class Settings:
    telegram_bot_token: str
    telegram_admin_chat_id: int

    monitor_enabled: bool
    monitor_interval_seconds: int
    monitor_cooldown_seconds: int
    monitor_recovery_enabled: bool

    network_dns_host: str
    network_urls: list[str]
    network_http_timeout_seconds: int
    network_tcp_host: str
    network_tcp_port: int
    network_ping_host: str
    network_ping_count: int
    network_ping_timeout_seconds: int
    network_ping_warn_loss_percent: float
    network_ping_crit_loss_percent: float
    network_ping_warn_avg_ms: float

    xray_service_name: str
    xray_command_timeout_seconds: int
    xray_restart_warn_count: int

    threshold_cpu_percent: float
    threshold_ram_percent: float
    threshold_disk_percent: float

    @staticmethod
    def from_env() -> "Settings":
        token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
        admin_chat_id = _env_int("TELEGRAM_ADMIN_CHAT_ID", 0)

        return Settings(
            telegram_bot_token=token,
            telegram_admin_chat_id=admin_chat_id,
            monitor_enabled=_env_bool("MONITOR_ENABLED", True),
            monitor_interval_seconds=max(5, _env_int("MONITOR_INTERVAL_SECONDS", 60)),
            monitor_cooldown_seconds=max(1, _env_int("MONITOR_COOLDOWN_SECONDS", 300)),
            monitor_recovery_enabled=_env_bool("MONITOR_RECOVERY_ENABLED", True),
            network_dns_host=os.getenv("NETWORK_DNS_HOST", "google.com").strip() or "google.com",
            network_urls=_env_csv(
                "NETWORK_URLS",
                ["https://www.google.com/generate_204", "https://1.1.1.1"],
            ),
            network_http_timeout_seconds=max(1, _env_int("NETWORK_HTTP_TIMEOUT_SECONDS", 5)),
            network_tcp_host=os.getenv("NETWORK_TCP_HOST", "1.1.1.1").strip() or "1.1.1.1",
            network_tcp_port=max(1, _env_int("NETWORK_TCP_PORT", 443)),
            network_ping_host=os.getenv("NETWORK_PING_HOST", "1.1.1.1").strip() or "1.1.1.1",
            network_ping_count=max(1, _env_int("NETWORK_PING_COUNT", 4)),
            network_ping_timeout_seconds=max(1, _env_int("NETWORK_PING_TIMEOUT_SECONDS", 3)),
            network_ping_warn_loss_percent=_env_float("NETWORK_PING_WARN_LOSS_PERCENT", 5.0),
            network_ping_crit_loss_percent=_env_float("NETWORK_PING_CRIT_LOSS_PERCENT", 20.0),
            network_ping_warn_avg_ms=_env_float("NETWORK_PING_WARN_AVG_MS", 250.0),
            xray_service_name=os.getenv("XRAY_SERVICE_NAME", "xray").strip() or "xray",
            xray_command_timeout_seconds=max(1, _env_int("XRAY_COMMAND_TIMEOUT_SECONDS", 5)),
            xray_restart_warn_count=max(0, _env_int("XRAY_RESTART_WARN_COUNT", 3)),
            threshold_cpu_percent=_env_float("THRESHOLD_CPU_PERCENT", 85.0),
            threshold_ram_percent=_env_float("THRESHOLD_RAM_PERCENT", 90.0),
            threshold_disk_percent=_env_float("THRESHOLD_DISK_PERCENT", 90.0),
        )

    def validate(self) -> None:
        if not self.telegram_bot_token:
            raise ValueError("TELEGRAM_BOT_TOKEN is required")
        if self.telegram_admin_chat_id == 0:
            raise ValueError("TELEGRAM_ADMIN_CHAT_ID is required")
