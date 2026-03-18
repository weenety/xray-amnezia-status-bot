package statusbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

        val firstHealthy = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(120),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("Xray", HealthLevel.OK, "active", "details")),
            ),
        )
        val recovery = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(180),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("Xray", HealthLevel.OK, "active", "details")),
            ),
        )

        assertEquals(0, firstHealthy.size)
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

    @Test
    fun `network warning requires three degraded checks before sending`() {
        val service = AlertingService(cooldownSeconds = 300, recoveryEnabled = true)
        val now = ZonedDateTime.now()

        val first = service.evaluate(
            StatusSnapshot(
                timestamp = now,
                overall = HealthLevel.WARN,
                checks = listOf(CheckResult("Network", HealthLevel.WARN, "pingLoss=25.0%", "details")),
            ),
        )
        val second = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(60),
                overall = HealthLevel.WARN,
                checks = listOf(CheckResult("Network", HealthLevel.WARN, "pingLoss=25.0%", "details")),
            ),
        )
        val third = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(120),
                overall = HealthLevel.WARN,
                checks = listOf(CheckResult("Network", HealthLevel.WARN, "pingLoss=25.0%", "details")),
            ),
        )

        assertEquals(0, first.size)
        assertEquals(0, second.size)
        assertEquals(1, third.size)
        assertEquals("Network", third.first().component)
        assertEquals(HealthLevel.WARN, third.first().level)
    }

    @Test
    fun `recovery requires two healthy checks after alert`() {
        val service = AlertingService(cooldownSeconds = 300, recoveryEnabled = true)
        val now = ZonedDateTime.now()

        service.evaluate(
            StatusSnapshot(
                timestamp = now,
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Xray", HealthLevel.CRIT, "inactive", "details")),
            ),
        )
        service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(60),
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Xray", HealthLevel.CRIT, "inactive", "details")),
            ),
        )

        val firstHealthy = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(120),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("Xray", HealthLevel.OK, "active", "details")),
            ),
        )
        val secondHealthy = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(180),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("Xray", HealthLevel.OK, "active", "details")),
            ),
        )

        assertEquals(0, firstHealthy.size)
        assertEquals(1, secondHealthy.size)
        assertEquals(HealthLevel.OK, secondHealthy.first().level)
    }

    @Test
    fun `single network hard probe failure does not immediately become critical`() {
        val checker = NetworkHealthChecker()
        val method = checker.javaClass.getDeclaredMethod(
            "evaluateCoreConnectivity",
            NetworkProbeData::class.java,
            MutableList::class.java,
            MutableList::class.java,
        )
        method.isAccessible = true

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val probe = NetworkProbeData(
            dnsOk = false,
            dnsError = "dns failed",
            httpOk = 2,
            httpTotal = 2,
            httpFailures = emptyList(),
            tcpOk = true,
            tcpError = null,
            ping = PingProbeResult(lossPercent = 0.0, avgMs = 10.0),
            gateway = "gw",
            defaultIface = "eth0",
            ifaceStats = null,
        )

        method.invoke(checker, probe, errors, warnings)

        assertTrue(errors.isEmpty())
        assertEquals(listOf("dns failed"), warnings)
    }

    @Test
    fun `confirmed recovery clears alert state even when recovery messages are disabled`() {
        val service = AlertingService(cooldownSeconds = 300, recoveryEnabled = false)
        val now = ZonedDateTime.now()

        service.evaluate(
            StatusSnapshot(
                timestamp = now,
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Xray", HealthLevel.CRIT, "inactive", "details")),
            ),
        )
        val initialAlert = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(60),
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Xray", HealthLevel.CRIT, "inactive", "details")),
            ),
        )
        val firstHealthy = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(120),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("Xray", HealthLevel.OK, "active", "details")),
            ),
        )
        val secondHealthy = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(180),
                overall = HealthLevel.OK,
                checks = listOf(CheckResult("Xray", HealthLevel.OK, "active", "details")),
            ),
        )
        service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(240),
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Xray", HealthLevel.CRIT, "inactive", "details")),
            ),
        )
        val secondAlert = service.evaluate(
            StatusSnapshot(
                timestamp = now.plusSeconds(300),
                overall = HealthLevel.CRIT,
                checks = listOf(CheckResult("Xray", HealthLevel.CRIT, "inactive", "details")),
            ),
        )

        assertEquals(1, initialAlert.size)
        assertEquals(0, firstHealthy.size)
        assertEquals(0, secondHealthy.size)
        assertEquals(1, secondAlert.size)
        assertEquals("Xray", secondAlert.first().component)
        assertEquals(HealthLevel.CRIT, secondAlert.first().level)
    }
}
