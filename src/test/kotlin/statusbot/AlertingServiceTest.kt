package statusbot

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.ZonedDateTime

class AlertingServiceTest {
    @Test
    fun `sends initial alert then cooldown suppresses duplicate`() {
        val service = AlertingService(cooldownSeconds = 300, recoveryEnabled = true)
        val now = ZonedDateTime.now()

        val first = service.evaluate(
            StatusSnapshot(
                timestamp = now,
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Network", HealthLevel.CRIT, "down", "details")),
            ),
        )
        assertEquals(1, first.size)

        val second = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(30),
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Network", HealthLevel.CRIT, "down", "details")),
            ),
        )
        assertEquals(0, second.size)
    }

    @Test
    fun `sends recovery when enabled`() {
        val service = AlertingService(cooldownSeconds = 300, recoveryEnabled = true)
        val now = ZonedDateTime.now()

        service.evaluate(
            StatusSnapshot(
                timestamp = now,
                overall = HealthLevel.WARN,
                checks = listOf(CheckResult("Xray", HealthLevel.WARN, "unstable", "details")),
            ),
        )

        val recovery = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(10),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("Xray", HealthLevel.OK, "active", "details")),
            ),
        )

        assertEquals(1, recovery.size)
        assertEquals("Xray", recovery.first().component)
        assertEquals(HealthLevel.OK, recovery.first().level)
    }
}
