package statusbot

import java.time.Duration
import java.time.ZonedDateTime

private const val DEFAULT_ALERT_CONSECUTIVE_DEGRADED_CHECKS = 2
private const val CPU_ALERT_CONSECUTIVE_DEGRADED_CHECKS = 3
private data class ComponentState(
    val lastObservedLevel: HealthLevel,
    val lastAlertAt: ZonedDateTime?,
    val alertedLevel: HealthLevel?,
    val consecutiveDegradedChecks: Int,
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
            if (previous?.alertedLevel != null && recoveryEnabled) {
                val event = AlertEvent(
                    component = check.component,
                    level = check.level,
                    message = "RECOVERY ${check.component} is OK. ${check.summary}",
                )
                return event to ComponentState(
                    lastObservedLevel = HealthLevel.OK,
                    lastAlertAt = previous.lastAlertAt,
                    alertedLevel = null,
                    consecutiveDegradedChecks = 0,
                )
            }
            return null to ComponentState(
                lastObservedLevel = HealthLevel.OK,
                lastAlertAt = previous?.lastAlertAt,
                alertedLevel = null,
                consecutiveDegradedChecks = 0,
            )
        }

        val consecutiveDegradedChecks = if (previous?.lastObservedLevel == HealthLevel.OK || previous == null) {
            1
        } else {
            previous.consecutiveDegradedChecks + 1
        }
        val requiredChecks = if (check.component == "CPU") {
            CPU_ALERT_CONSECUTIVE_DEGRADED_CHECKS
        } else {
            DEFAULT_ALERT_CONSECUTIVE_DEGRADED_CHECKS
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
            )
        }

        return null to ComponentState(
            lastObservedLevel = check.level,
            lastAlertAt = previous?.lastAlertAt,
            alertedLevel = previous?.alertedLevel,
            consecutiveDegradedChecks = consecutiveDegradedChecks,
        )
    }
}
