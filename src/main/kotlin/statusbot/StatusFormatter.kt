package statusbot

import java.time.format.DateTimeFormatter
import java.util.Locale

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
    snapshot.traffic?.let { traffic ->
        lines += "Traffic: ${formatTraffic(traffic)}"
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

private fun formatTraffic(traffic: TrafficSnapshot): String {
    val totals = traffic.totals
    val parts = mutableListOf<String>()
    parts += "${formatBytes(totals.rxBytes + totals.txBytes)} total"

    val rates = traffic.rates
    if (rates != null) {
        parts += "Now ${formatBitsPerSecond((rates.rxBytesPerSecond + rates.txBytesPerSecond) * 8.0)}"
    } else {
        parts += "Now n/a"
    }

    return parts.joinToString(" | ")
}

private fun classifyTimingLevel(name: String, durationMs: Long): HealthLevel {
    val threshold = TIMING_THRESHOLDS[name] ?: DEFAULT_TIMING_THRESHOLD
    return when {
        durationMs >= threshold.critMs -> HealthLevel.CRIT
        durationMs >= threshold.warnMs -> HealthLevel.WARN
        else -> HealthLevel.OK
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) {
        return "$bytes B"
    }

    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

private fun formatBitsPerSecond(bitsPerSecond: Double): String {
    val units = listOf("bps", "Kbps", "Mbps", "Gbps")
    var value = bitsPerSecond
    var unitIndex = 0
    while (value >= 1000.0 && unitIndex < units.lastIndex) {
        value /= 1000.0
        unitIndex += 1
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
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
