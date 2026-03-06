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
    fun `includes timing diagnostics when provided`() {
        val snapshot = StatusSnapshot(
            timestamp = ZonedDateTime.now(),
            overall = HealthLevel.OK,
            checks = listOf(
                CheckResult("Network", HealthLevel.OK, "ok", "details"),
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
    }
}
