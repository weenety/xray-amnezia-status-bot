package statusbot

import java.time.Duration
import java.time.ZonedDateTime

private data class ComponentState(
    val level: HealthLevel,
    val lastAlertAt: ZonedDateTime?,
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
            if (previous != null && previous.level != HealthLevel.OK && recoveryEnabled) {
                val event = AlertEvent(
                    component = check.component,
                    level = check.level,
                    message = "RECOVERY ${check.component} is OK. ${check.summary}",
                )
                return event to ComponentState(HealthLevel.OK, previous.lastAlertAt)
            }
            return null to ComponentState(HealthLevel.OK, previous?.lastAlertAt)
        }

        var shouldSend = previous == null || previous.level == HealthLevel.OK || previous.level != check.level
        if (!shouldSend && previous?.lastAlertAt != null) {
            shouldSend = Duration.between(previous.lastAlertAt, now) >= cooldown
        }

        if (shouldSend) {
            val event = AlertEvent(
                component = check.component,
                level = check.level,
                message = "ALERT ${check.component} is ${check.level.name}. ${check.summary} | ${check.details}",
            )
            return event to ComponentState(check.level, now)
        }

        return null to ComponentState(check.level, previous?.lastAlertAt ?: now)
    }
}
