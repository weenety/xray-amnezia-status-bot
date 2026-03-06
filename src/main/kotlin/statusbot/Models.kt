package statusbot

import java.time.ZonedDateTime

enum class HealthLevel(val severity: Int) {
    OK(0),
    WARN(1),
    CRIT(2),
}

enum class AttributionKind {
    NO_ISSUE,
    VPS_SIDE,
    USER_SIDE_OR_ROUTE,
    MIXED,
    INCONCLUSIVE,
}

enum class ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH,
}

data class IncidentAttribution(
    val kind: AttributionKind,
    val confidence: ConfidenceLevel,
    val reason: String,
)

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
    val attribution: IncidentAttribution = IncidentAttribution(
        kind = AttributionKind.INCONCLUSIVE,
        confidence = ConfidenceLevel.LOW,
        reason = "insufficient data",
    ),
)

data class AlertEvent(
    val component: String,
    val level: HealthLevel,
    val message: String,
)
