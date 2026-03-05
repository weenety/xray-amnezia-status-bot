from __future__ import annotations

from html import escape

from .models import StatusSnapshot


def format_status(snapshot: StatusSnapshot) -> str:
    ok_count = sum(1 for c in snapshot.checks if c.level.name == "OK")
    warn_count = sum(1 for c in snapshot.checks if c.level.name == "WARN")
    crit_count = sum(1 for c in snapshot.checks if c.level.name == "CRIT")

    lines: list[str] = [f"Overall: {snapshot.overall.name}"]
    lines.append(f"Checks: OK {ok_count} | WARN {warn_count} | CRIT {crit_count}")
    for check in snapshot.checks:
        lines.append(f"{check.component}: {check.level.name} - {check.summary}")

    lines.append(f"Updated: {snapshot.timestamp.strftime('%Y-%m-%d %H:%M:%S %Z')}")
    body = "\n".join(lines)
    return f"<pre>{escape(body)}</pre>"
