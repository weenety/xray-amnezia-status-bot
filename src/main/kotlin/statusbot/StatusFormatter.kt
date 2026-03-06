package statusbot

import java.time.format.DateTimeFormatter

private val STATUS_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

fun formatStatus(snapshot: StatusSnapshot): String {
    val okCount = snapshot.checks.count { it.level == HealthLevel.OK }
    val warnCount = snapshot.checks.count { it.level == HealthLevel.WARN }
    val critCount = snapshot.checks.count { it.level == HealthLevel.CRIT }

    val lines = mutableListOf<String>()
    lines += "Overall: ${snapshot.overall.name}"
    lines += "Checks: OK $okCount | WARN $warnCount | CRIT $critCount"
    snapshot.checks.forEach { check ->
        lines += "${check.component}: ${check.level.name} - ${check.summary}"
    }
    if (snapshot.timingsMs.isNotEmpty()) {
        val timings = snapshot.timingsMs.entries
            .sortedBy { it.key }
            .joinToString(" | ") { "${it.key} ${it.value}ms" }
        lines += "Timings: $timings"
    }
    lines += "Updated: ${snapshot.timestamp.format(STATUS_TIME_FORMAT)}"

    return "<pre>${escapeHtml(lines.joinToString("\n"))}</pre>"
}

private fun escapeHtml(value: String): String {
    return buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#x27;")
                else -> append(ch)
            }
        }
    }
}
