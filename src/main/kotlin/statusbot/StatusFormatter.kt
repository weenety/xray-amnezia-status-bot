package statusbot

import java.time.format.DateTimeFormatter

private val STATUS_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
private data class TimingThreshold(val warnMs: Long, val critMs: Long)
private val TIMING_THRESHOLDS: Map<String, TimingThreshold> = mapOf(
    "Network" to TimingThreshold(warnMs = 4500, critMs = 7000),
    "System" to TimingThreshold(warnMs = 300, critMs = 500),
    "Xray" to TimingThreshold(warnMs = 150, critMs = 500),
    "AmneziaWG" to TimingThreshold(warnMs = 200, critMs = 600),
)
private val DEFAULT_TIMING_THRESHOLD = TimingThreshold(warnMs = 500, critMs = 1000)

fun formatStatus(snapshot: StatusSnapshot): String {
    val okCount = snapshot.checks.count { it.level == HealthLevel.OK }
    val warnCount = snapshot.checks.count { it.level == HealthLevel.WARN }
    val critCount = snapshot.checks.count { it.level == HealthLevel.CRIT }
    val degraded = snapshot.overall != HealthLevel.OK || warnCount > 0 || critCount > 0
    val byComponent = snapshot.checks.associateBy { it.component }

    val lines = mutableListOf<String>()
    lines += "Overall: ${snapshot.overall.name}"
    lines += "Checks: OK $okCount | WARN $warnCount | CRIT $critCount"
    if (degraded) {
        lines += "Attribution: ${snapshot.attribution.kind.name} (${snapshot.attribution.confidence.name}) - ${snapshot.attribution.reason}"
        snapshot.checks.forEach { check ->
            lines += "${check.component}: ${check.level.name} - ${check.summary}"
        }
    } else {
        val serviceParts = listOf("Xray", "AmneziaWG", "Network")
            .mapNotNull { component ->
                byComponent[component]?.let { "$component=${it.level.name}" }
            }
        if (serviceParts.isNotEmpty()) {
            lines += "Services: ${serviceParts.joinToString(" | ")}"
        }

        val systemParts = listOf("CPU", "RAM", "Disk")
            .mapNotNull { component ->
                byComponent[component]?.let { "${it.component} ${it.summary}" }
            }
        if (systemParts.isNotEmpty()) {
            lines += "System: ${systemParts.joinToString(" | ")}"
        }
    }
    if (degraded && snapshot.timingsMs.isNotEmpty()) {
        val timingLevels = snapshot.timingsMs.entries
            .sortedBy { it.key }
            .map { (name, ms) -> name to classifyTimingLevel(name, ms) }
        val okCount = timingLevels.count { it.second == HealthLevel.OK }
        val warnCount = timingLevels.count { it.second == HealthLevel.WARN }
        val critCount = timingLevels.count { it.second == HealthLevel.CRIT }
        lines += "TimingChecks: OK $okCount | WARN $warnCount | CRIT $critCount"
        lines += "Timings: ${timingLevels.joinToString(" | ") { "${it.first}=${it.second.name}" }}"
    }
    lines += "Updated: ${snapshot.timestamp.format(STATUS_TIME_FORMAT)}"

    return "<pre>${escapeHtml(lines.joinToString("\n"))}</pre>"
}

private fun classifyTimingLevel(name: String, durationMs: Long): HealthLevel {
    val threshold = TIMING_THRESHOLDS[name] ?: DEFAULT_TIMING_THRESHOLD
    return when {
        durationMs >= threshold.critMs -> HealthLevel.CRIT
        durationMs >= threshold.warnMs -> HealthLevel.WARN
        else -> HealthLevel.OK
    }
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
