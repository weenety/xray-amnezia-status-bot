package statusbot

import kotlin.test.Test
import kotlin.test.assertTrue
import java.time.ZonedDateTime

class StatusFormatterTest {
    @Test
    fun `wraps output in pre and escapes html`() {
        val snapshot = StatusSnapshot(
            timestamp = ZonedDateTime.now(),
            overall = HealthLevel.WARN,
            checks = listOf(
                CheckResult("Network", HealthLevel.WARN, "latency < 250ms", "details"),
            ),
        )

        val text = formatStatus(snapshot)

        assertTrue(text.startsWith("<pre>"))
        assertTrue(text.endsWith("</pre>"))
        assertTrue(text.contains("&lt;"))
        assertTrue(text.contains("Attribution:"))
    }

    @Test
    fun `includes timing diagnostics when degraded`() {
        val snapshot = StatusSnapshot(
            timestamp = ZonedDateTime.now(),
            overall = HealthLevel.WARN,
            checks = listOf(
                CheckResult("Network", HealthLevel.WARN, "degraded", "details"),
            ),
            timingsMs = mapOf("Network" to 5100, "Xray" to 7, "System" to 600),
        )

        val text = formatStatus(snapshot)
        assertTrue(text.contains("TimingChecks:"))
        assertTrue(text.contains("OK 1 | WARN 1 | CRIT 1"))
        assertTrue(text.contains("Network=WARN"))
        assertTrue(text.contains("Xray=OK"))
        assertTrue(text.contains("System=CRIT"))
        assertTrue(text.contains("Attribution:"))
        assertTrue(text.contains("Network: WARN - degraded"))
    }

    @Test
    fun `renders compact healthy output`() {
        val snapshot = StatusSnapshot(
            timestamp = ZonedDateTime.now(),
            overall = HealthLevel.OK,
            checks = listOf(
                CheckResult("Network", HealthLevel.OK, "dns=ok, http=2/2, tcp=ok", "details"),
                CheckResult("Xray", HealthLevel.OK, "active", "details"),
                CheckResult("AmneziaWG", HealthLevel.OK, "running", "details"),
                CheckResult("CPU", HealthLevel.OK, "5.0%", "details"),
                CheckResult("RAM", HealthLevel.OK, "50.0%", "details"),
                CheckResult("Disk", HealthLevel.OK, "10.0%", "details"),
            ),
            timingsMs = mapOf("Network" to 120, "Xray" to 40),
        )

        val text = formatStatus(snapshot)
        assertTrue(!text.contains("Attribution:"))
        assertTrue(!text.contains("TimingChecks:"))
        assertTrue(!text.contains("Timings:"))
        assertTrue(text.contains("Services: Xray=OK | AmneziaWG=OK | Network=OK"))
        assertTrue(text.contains("System: CPU 5.0% | RAM 50.0% | Disk 10.0%"))
        assertTrue(!text.contains("Network: OK -"))
        assertTrue(!text.contains("CPU: OK -"))
    }
}
