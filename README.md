# statusbot

Lightweight Telegram bot for VPS health monitoring.

## Features

- `/status` command for current VPS state
- network checks (DNS + HTTP + TCP + ping loss/latency)
- `xray` service checks via `systemctl` (+ restart count)
- CPU/RAM/Disk health checks
- automatic alerts with cooldown
- recovery notifications

## Configure

Use environment variables:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_ADMIN_CHAT_ID`
- `MONITOR_INTERVAL_SECONDS` (default: `60`)
- `MONITOR_COOLDOWN_SECONDS` (default: `300`)
- `MONITOR_RECOVERY_ENABLED` (default: `true`)
- `XRAY_SERVICE_NAME` (default: `xray`)
- `XRAY_COMMAND_TIMEOUT_SECONDS` (default: `5`)
- `THRESHOLD_CPU_PERCENT` (default: `85`)
- `THRESHOLD_RAM_PERCENT` (default: `90`)
- `THRESHOLD_DISK_PERCENT` (default: `90`)

Network probe defaults are built-in (DNS + HTTP + TCP + ping) to keep `env` minimal.

## Run

```bash
python -m venv .venv
source .venv/bin/activate
pip install -e .
statusbot
```
