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
    }

    @Test
    fun `includes timing diagnostics when provided`() {
        val snapshot = StatusSnapshot(
            timestamp = ZonedDateTime.now(),
            overall = HealthLevel.OK,
            checks = listOf(
                CheckResult("Network", HealthLevel.OK, "ok", "details"),
            ),
            timingsMs = mapOf("Network" to 42, "Xray" to 7),
        )

        val text = formatStatus(snapshot)
        assertTrue(text.contains("Timings:"))
        assertTrue(text.contains("Network 42ms"))
        assertTrue(text.contains("Xray 7ms"))
    }
}
