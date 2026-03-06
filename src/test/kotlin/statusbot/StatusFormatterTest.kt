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
}
