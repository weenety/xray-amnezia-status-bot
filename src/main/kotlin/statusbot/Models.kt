package statusbot

import java.time.ZonedDateTime

enum class HealthLevel(val severity: Int) {
    OK(0),
    WARN(1),
    CRIT(2),
}

data class CheckResult(
    val component: String,
    val level: HealthLevel,
    val summary: String,
    val details: String,
)

data class StatusSnapshot(
    val timestamp: ZonedDateTime,
    val overall: HealthLevel,
    val checks: List<CheckResult>,
    val timingsMs: Map<String, Long> = emptyMap(),
)

data class AlertEvent(
    val component: String,
    val level: HealthLevel,
    val message: String,
)
