# StatusBot

Minimal Telegram bot for VPS health monitoring on Kotlin/JVM.

## What It Monitors

- `Network`: DNS, HTTP, TCP, ping loss/latency
- `Xray`: `systemctl` status + restart count
- `AmneziaWG`: Docker container state (`amnezia-awg`)
- `System`: CPU, RAM, Disk usage
- incident attribution (`VPS_SIDE`, `USER_SIDE_OR_ROUTE`, `MIXED`, etc.)

The `/status` command is admin-only and returns a compact healthy view, with expanded diagnostics only when there is degradation.

## Configuration

Only 2 environment variables are required:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_ADMIN_CHAT_ID`

Template: [`deploy/statusbot.env.example`](deploy/statusbot.env.example)

## Local Run

```bash
gradle clean build
java -jar build/libs/statusbot.jar
```

## Example `/status`

```text
Overall: OK
Checks: OK 6 | WARN 0 | CRIT 0
Services: Xray=OK | AmneziaWG=OK | Network=OK
System: CPU 10.0% | RAM 52.2% | Disk 13.9%
Updated: 2026-03-06 14:29:11 MSK
```

## Deployment (systemd)

Files:

- unit: [`deploy/statusbot.service`](deploy/statusbot.service)
- env: [`deploy/statusbot.env.example`](deploy/statusbot.env.example)

The unit is tuned for low memory (`-Xms16m -Xmx80m`, `SerialGC`).

## Defaults

- monitor interval: `60s`
- alert cooldown: `300s`
- thresholds: CPU `85%`, RAM `90%`, Disk `90%`
- CPU sample window: `1000ms`
- alert confirmation: `2` consecutive degraded checks
- CPU alert confirmation: `3` consecutive degraded checks
