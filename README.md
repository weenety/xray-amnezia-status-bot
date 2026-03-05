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
- `NETWORK_DNS_HOST` (default: `google.com`)
- `NETWORK_URLS` (default: `https://www.google.com/generate_204,https://cp.cloudflare.com/generate_204`)
- `NETWORK_HTTP_TIMEOUT_SECONDS` (default: `5`)
- `NETWORK_TCP_HOST` (default: `1.1.1.1`)
- `NETWORK_TCP_PORT` (default: `443`)
- `NETWORK_PING_HOST` (default: `1.1.1.1`)
- `NETWORK_PING_COUNT` (default: `4`)
- `NETWORK_PING_TIMEOUT_SECONDS` (default: `3`)
- `NETWORK_PING_WARN_LOSS_PERCENT` (default: `5`)
- `NETWORK_PING_CRIT_LOSS_PERCENT` (default: `20`)
- `NETWORK_PING_WARN_AVG_MS` (default: `250`)
- `XRAY_SERVICE_NAME` (default: `xray`)
- `XRAY_COMMAND_TIMEOUT_SECONDS` (default: `5`)
- `XRAY_RESTART_WARN_COUNT` (default: `3`)
- `THRESHOLD_CPU_PERCENT` (default: `85`)
- `THRESHOLD_RAM_PERCENT` (default: `90`)
- `THRESHOLD_DISK_PERCENT` (default: `90`)

## Run

```bash
python -m venv .venv
source .venv/bin/activate
pip install -e .
statusbot
```
