# statusbot

Lightweight Telegram bot for VPS health monitoring, implemented in Kotlin/JVM.

## Features

- `/status` command for current VPS state (admin-only)
- network checks (DNS + HTTP + TCP + ping loss/latency)
- `xray` service checks via `systemctl` (+ restart count)
- optional `amnezia-awg` Docker container check
- CPU/RAM/Disk health checks
- incident attribution (`VPS_SIDE` vs `USER_SIDE_OR_ROUTE` with confidence)
- automatic alerts with cooldown
- recovery notifications

## Configure

Use only these environment variables:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_ADMIN_CHAT_ID`

Everything else is fixed in code defaults:
- monitor loop: enabled, interval `60s`, cooldown `300s`, recovery notifications enabled
- `xray` check: service name `xray`, command timeout `5s`
- AmneziaWG check: enabled, container `amnezia-awg`, docker timeout `5s`
- thresholds: CPU `85%`, RAM `90%`, Disk `90%`

## Build and Run

```bash
gradle clean build
java -jar build/libs/statusbot.jar
```

## Deployment

- Environment template: `deploy/statusbot.env.example`
- Systemd unit: `deploy/statusbot.service`
- Unit is tuned for low-memory JVM profile (`-Xms16m -Xmx80m`, `SerialGC`).
