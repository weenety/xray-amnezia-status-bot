package statusbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncidentAttributionEvaluatorTest {
    @Test
    fun `returns no issue when all checks are healthy`() {
        val checks = listOf(
            CheckResult("Network", HealthLevel.OK, "dns=ok, http=2/2, tcp=ok, pingLoss=0.0%", "details"),
            CheckResult("Xray", HealthLevel.OK, "active", "details"),
            CheckResult("AmneziaWG", HealthLevel.OK, "running", "details"),
            CheckResult("CPU", HealthLevel.OK, "10.0%", "details"),
            CheckResult("RAM", HealthLevel.OK, "30.0%", "details"),
            CheckResult("Disk", HealthLevel.OK, "20.0%", "details"),
        )

        val attribution = IncidentAttributionEvaluator.evaluate(checks)

        assertEquals(AttributionKind.NO_ISSUE, attribution.kind)
        assertEquals(ConfidenceLevel.HIGH, attribution.confidence)
    }

    @Test
    fun `classifies vpn failure as vps side with high confidence when network is healthy`() {
        val checks = listOf(
            CheckResult("Network", HealthLevel.OK, "dns=ok, http=2/2, tcp=ok, pingLoss=0.0%", "details"),
            CheckResult("Xray", HealthLevel.CRIT, "inactive", "details"),
            CheckResult("AmneziaWG", HealthLevel.OK, "running", "details"),
        )

        val attribution = IncidentAttributionEvaluator.evaluate(checks)

        assertEquals(AttributionKind.VPS_SIDE, attribution.kind)
        assertEquals(ConfidenceLevel.HIGH, attribution.confidence)
        assertTrue(attribution.reason.contains("vpn services degraded"))
    }

    @Test
    fun `classifies network warn with healthy vpn as user side or route`() {
        val checks = listOf(
            CheckResult("Network", HealthLevel.WARN, "dns=ok, http=2/2, tcp=ok, pingLoss=12.0%", "details"),
            CheckResult("Xray", HealthLevel.OK, "active", "details"),
            CheckResult("AmneziaWG", HealthLevel.OK, "running", "details"),
        )

        val attribution = IncidentAttributionEvaluator.evaluate(checks)

        assertEquals(AttributionKind.USER_SIDE_OR_ROUTE, attribution.kind)
        assertEquals(ConfidenceLevel.MEDIUM, attribution.confidence)
    }

    @Test
    fun `classifies resource pressure as vps side`() {
        val checks = listOf(
            CheckResult("Network", HealthLevel.OK, "dns=ok, http=2/2, tcp=ok, pingLoss=0.0%", "details"),
            CheckResult("Xray", HealthLevel.OK, "active", "details"),
            CheckResult("AmneziaWG", HealthLevel.OK, "running", "details"),
            CheckResult("CPU", HealthLevel.CRIT, "95.0%", "details"),
        )

        val attribution = IncidentAttributionEvaluator.evaluate(checks)

        assertEquals(AttributionKind.VPS_SIDE, attribution.kind)
        assertEquals(ConfidenceLevel.HIGH, attribution.confidence)
        assertTrue(attribution.reason.contains("resource pressure"))
    }

    @Test
    fun `does not classify probe limitation warning as user side`() {
        val checks = listOf(
            CheckResult(
                "Network",
                HealthLevel.WARN,
                "dns=ok, http=2/2, tcp=ok",
                "defaultIface=eth0, defaultGw=1.1.1.1, warn=ping(1.1.1.1): ping not installed",
            ),
            CheckResult("Xray", HealthLevel.OK, "active", "details"),
            CheckResult("AmneziaWG", HealthLevel.OK, "running", "details"),
        )

        val attribution = IncidentAttributionEvaluator.evaluate(checks)

        assertEquals(AttributionKind.INCONCLUSIVE, attribution.kind)
        assertEquals(ConfidenceLevel.LOW, attribution.confidence)
    }
}
