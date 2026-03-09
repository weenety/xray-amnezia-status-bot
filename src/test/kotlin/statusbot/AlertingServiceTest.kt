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
        assertEquals(0, first.size)

        val second = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(30),
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Network", HealthLevel.CRIT, "down", "details")),
            ),
        )
        assertEquals(1, second.size)

        val third = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(60),
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Network", HealthLevel.CRIT, "down", "details")),
            ),
        )
        assertEquals(0, third.size)
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
        service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(60),
                overall = HealthLevel.WARN,
                checks = listOf(CheckResult("Xray", HealthLevel.WARN, "unstable", "details")),
            ),
        )

        val recovery = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(120),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("Xray", HealthLevel.OK, "active", "details")),
            ),
        )

        assertEquals(1, recovery.size)
        assertEquals("Xray", recovery.first().component)
        assertEquals(HealthLevel.OK, recovery.first().level)
    }

    @Test
    fun `cpu alert requires consecutive degraded checks before sending`() {
        val service = AlertingService(cooldownSeconds = 300, recoveryEnabled = true)
        val now = ZonedDateTime.now()

        val first = service.evaluate(
            StatusSnapshot(
                timestamp = now,
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("CPU", HealthLevel.CRIT, "92.0%", "cpuLoad=92.0%")),
            ),
        )
        val second = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(60),
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("CPU", HealthLevel.CRIT, "95.0%", "cpuLoad=95.0%")),
            ),
        )
        val third = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(120),
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("CPU", HealthLevel.CRIT, "97.0%", "cpuLoad=97.0%")),
            ),
        )

        assertEquals(0, first.size)
        assertEquals(0, second.size)
        assertEquals(1, third.size)
        assertEquals("CPU", third.first().component)
        assertEquals(HealthLevel.CRIT, third.first().level)
    }

    @Test
    fun `recovery is not sent when cpu spike never became an alert`() {
        val service = AlertingService(cooldownSeconds = 300, recoveryEnabled = true)
        val now = ZonedDateTime.now()

        service.evaluate(
            StatusSnapshot(
                timestamp = now,
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("CPU", HealthLevel.CRIT, "90.0%", "cpuLoad=90.0%")),
            ),
        )

        val recovery = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(60),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("CPU", HealthLevel.OK, "12.0%", "cpuLoad=12.0%")),
            ),
        )

        assertEquals(0, recovery.size)
    }

    @Test
    fun `recovery is not sent when transient network issue never became an alert`() {
        val service = AlertingService(cooldownSeconds = 300, recoveryEnabled = true)
        val now = ZonedDateTime.now()

        service.evaluate(
            StatusSnapshot(
                timestamp = now,
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Network", HealthLevel.CRIT, "pingLoss=25.0%", "details")),
            ),
        )

        val recovery = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(60),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("Network", HealthLevel.OK, "pingLoss=0.0%", "details")),
            ),
        )

        assertEquals(0, recovery.size)
    }
}
