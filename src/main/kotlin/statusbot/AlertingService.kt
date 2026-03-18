package statusbot

import java.time.Duration
import java.time.ZonedDateTime

private const val DEFAULT_ALERT_CONSECUTIVE_DEGRADED_CHECKS = 2
private const val CPU_ALERT_CONSECUTIVE_DEGRADED_CHECKS = 3
private const val NETWORK_WARN_ALERT_CONSECUTIVE_DEGRADED_CHECKS = 3
private const val DEFAULT_RECOVERY_CONSECUTIVE_OK_CHECKS = 2
private data class ComponentState(
    val lastObservedLevel: HealthLevel,
    val lastAlertAt: ZonedDateTime?,
    val alertedLevel: HealthLevel?,
    val consecutiveDegradedChecks: Int,
    val consecutiveOkChecks: Int,
)

class AlertingService(cooldownSeconds: Int, private val recoveryEnabled: Boolean) {
    private val cooldown: Duration = Duration.ofSeconds(maxOf(1, cooldownSeconds).toLong())
    private val states: MutableMap<String, ComponentState> = mutableMapOf()

    @Synchronized
    fun evaluate(snapshot: StatusSnapshot): List<AlertEvent> {
        val events = mutableListOf<AlertEvent>()
        val now = snapshot.timestamp

        for (check in snapshot.checks) {
            val previous = states[check.component]
            val (event, state) = evaluateComponent(previous, check, now)
            states[check.component] = state
            if (event != null) {
                events += event
            }
        }

        return events
    }

    private fun evaluateComponent(
        previous: ComponentState?,
        check: CheckResult,
        now: ZonedDateTime,
    ): Pair<AlertEvent?, ComponentState> {
        if (check.level == HealthLevel.OK) {
            val consecutiveOkChecks = if (previous?.lastObservedLevel == HealthLevel.OK) {
                previous.consecutiveOkChecks + 1
            } else {
                1
            }
            if (previous?.alertedLevel != null && consecutiveOkChecks >= DEFAULT_RECOVERY_CONSECUTIVE_OK_CHECKS) {
                val event = if (recoveryEnabled) {
                    AlertEvent(
                        component = check.component,
                        level = check.level,
                        message = "RECOVERY ${check.component} is OK. ${check.summary}",
                    )
                } else {
                    null
                }
                return event to ComponentState(
                    lastObservedLevel = HealthLevel.OK,
                    lastAlertAt = previous.lastAlertAt,
                    alertedLevel = null,
                    consecutiveDegradedChecks = 0,
                    consecutiveOkChecks = 0,
                )
            }
            return null to ComponentState(
                lastObservedLevel = HealthLevel.OK,
                lastAlertAt = previous?.lastAlertAt,
                alertedLevel = previous?.alertedLevel,
                consecutiveDegradedChecks = 0,
                consecutiveOkChecks = if (previous?.alertedLevel != null) consecutiveOkChecks else 0,
            )
        }

        val consecutiveDegradedChecks = if (previous?.lastObservedLevel == HealthLevel.OK || previous == null) {
            1
        } else {
            previous.consecutiveDegradedChecks + 1
        }
        val requiredChecks = when {
            check.component == "CPU" -> CPU_ALERT_CONSECUTIVE_DEGRADED_CHECKS
            check.component == "Network" && check.level == HealthLevel.WARN -> {
                NETWORK_WARN_ALERT_CONSECUTIVE_DEGRADED_CHECKS
            }

            else -> DEFAULT_ALERT_CONSECUTIVE_DEGRADED_CHECKS
        }
        var shouldSend = previous?.alertedLevel == null && consecutiveDegradedChecks >= requiredChecks
        if (!shouldSend && previous?.alertedLevel != null && previous.alertedLevel != check.level) {
            shouldSend = true
        }
        if (!shouldSend && previous?.lastAlertAt != null && previous.alertedLevel != null) {
            shouldSend = Duration.between(previous.lastAlertAt, now) >= cooldown
        }

        if (shouldSend) {
            val event = AlertEvent(
                component = check.component,
                level = check.level,
                message = "ALERT ${check.component} is ${check.level.name}. ${check.summary} | ${check.details}",
            )
            return event to ComponentState(
                lastObservedLevel = check.level,
                lastAlertAt = now,
                alertedLevel = check.level,
                consecutiveDegradedChecks = consecutiveDegradedChecks,
                consecutiveOkChecks = 0,
            )
        }

        return null to ComponentState(
            lastObservedLevel = check.level,
            lastAlertAt = previous?.lastAlertAt,
            alertedLevel = previous?.alertedLevel,
            consecutiveDegradedChecks = consecutiveDegradedChecks,
            consecutiveOkChecks = 0,
        )
    }
}
