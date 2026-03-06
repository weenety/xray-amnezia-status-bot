package statusbot

import kotlin.test.Test
import kotlin.test.assertTrue
import java.time.ZoneId
import java.time.ZonedDateTime

class StatusOutputSmokeTest {
    @Test
    fun `healthy status is compact in smoke scenario`() {
        val checks = listOf(
            CheckResult("Network", HealthLevel.OK, "dns=ok, http=2/2, tcp=ok, pingLoss=0.0%", "details"),
            CheckResult("Xray", HealthLevel.OK, "active", "details"),
            CheckResult("AmneziaWG", HealthLevel.OK, "running", "details"),
            CheckResult("CPU", HealthLevel.OK, "9.5%", "details"),
            CheckResult("RAM", HealthLevel.OK, "54.8%", "details"),
            CheckResult("Disk", HealthLevel.OK, "13.9%", "details"),
        )
        val snapshot = StatusSnapshot(
            timestamp = fixedTimestamp(),
            overall = checks.maxByOrNull { it.level.severity }?.level ?: HealthLevel.OK,
            checks = checks,
            timingsMs = mapOf("Network" to 3200, "Xray" to 80, "AmneziaWG" to 120, "System" to 230),
            attribution = IncidentAttributionEvaluator.evaluate(checks),
        )

        val text = formatStatus(snapshot)

        assertTrue(text.contains("Overall: OK"))
        assertTrue(text.contains("Services: Xray=OK | AmneziaWG=OK | Network=OK"))
        assertTrue(text.contains("System: CPU 9.5% | RAM 54.8% | Disk 13.9%"))
        assertTrue(!text.contains("Attribution:"))
        assertTrue(!text.contains("TimingChecks:"))
        assertTrue(!text.contains("Timings:"))
    }

    @Test
    fun `probe limitation warning keeps detailed output in smoke scenario`() {
        val checks = listOf(
            CheckResult(
                "Network",
                HealthLevel.WARN,
                "dns=ok, http=2/2, tcp=ok",
                "defaultIface=eth0, defaultGw=1.1.1.1, warn=ping(1.1.1.1): ping not installed",
            ),
            CheckResult("Xray", HealthLevel.OK, "active", "details"),
            CheckResult("AmneziaWG", HealthLevel.OK, "running", "details"),
            CheckResult("CPU", HealthLevel.OK, "12.0%", "details"),
            CheckResult("RAM", HealthLevel.OK, "55.0%", "details"),
            CheckResult("Disk", HealthLevel.OK, "14.0%", "details"),
        )
        val snapshot = StatusSnapshot(
            timestamp = fixedTimestamp(),
            overall = checks.maxByOrNull { it.level.severity }?.level ?: HealthLevel.OK,
            checks = checks,
            timingsMs = mapOf("Network" to 3500, "Xray" to 70, "AmneziaWG" to 110, "System" to 220),
            attribution = IncidentAttributionEvaluator.evaluate(checks),
        )

        val text = formatStatus(snapshot)

        assertTrue(text.contains("Overall: WARN"))
        assertTrue(text.contains("Attribution: INCONCLUSIVE (LOW)"))
        assertTrue(text.contains("Network: WARN - dns=ok, http=2/2, tcp=ok"))
        assertTrue(text.contains("TimingChecks:"))
        assertTrue(text.contains("Timings:"))
    }

    private fun fixedTimestamp(): ZonedDateTime {
        return ZonedDateTime.of(2026, 3, 6, 14, 51, 33, 0, ZoneId.of("Europe/Moscow"))
    }
}
