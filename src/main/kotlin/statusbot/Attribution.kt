package statusbot

object IncidentAttributionEvaluator {
    fun evaluate(checks: List<CheckResult>): IncidentAttribution {
        if (checks.isEmpty()) {
            return IncidentAttribution(
                kind = AttributionKind.INCONCLUSIVE,
                confidence = ConfidenceLevel.LOW,
                reason = "no checks available",
            )
        }

        val byComponent = checks.associateBy { it.component }
        val degraded = checks.filter { it.level != HealthLevel.OK }
        if (degraded.isEmpty()) {
            return IncidentAttribution(
                kind = AttributionKind.NO_ISSUE,
                confidence = ConfidenceLevel.HIGH,
                reason = "all server-side checks are healthy",
            )
        }

        val network = byComponent["Network"]
        val xray = byComponent["Xray"]
        val amnezia = byComponent["AmneziaWG"]
        val vpnIssues = listOfNotNull(xray, amnezia).filter { it.level != HealthLevel.OK }
        val resourceIssues = listOfNotNull(
            byComponent["CPU"],
            byComponent["RAM"],
            byComponent["Disk"],
        ).filter { it.level != HealthLevel.OK }

        if (vpnIssues.isNotEmpty()) {
            val affected = vpnIssues.joinToString(", ") { "${it.component}=${it.level.name}" }
            val confidence = if (network?.level == HealthLevel.OK) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
            val reason = if (network?.level == HealthLevel.OK) {
                "vpn services degraded ($affected) while egress checks are healthy"
            } else {
                "vpn services degraded ($affected); network check is ${network?.level?.name ?: "unknown"}"
            }
            return IncidentAttribution(
                kind = AttributionKind.VPS_SIDE,
                confidence = confidence,
                reason = reason,
            )
        }

        if (network?.level == HealthLevel.CRIT) {
            if (resourceIssues.isNotEmpty()) {
                val affected = resourceIssues.joinToString(", ") { "${it.component}=${it.level.name}" }
                return IncidentAttribution(
                    kind = AttributionKind.MIXED,
                    confidence = ConfidenceLevel.MEDIUM,
                    reason = "network failed and host resources degraded ($affected)",
                )
            }
            return IncidentAttribution(
                kind = AttributionKind.VPS_SIDE,
                confidence = ConfidenceLevel.HIGH,
                reason = "server-side network probes failed (${network.summary})",
            )
        }

        if (resourceIssues.isNotEmpty()) {
            val affected = resourceIssues.joinToString(", ") { "${it.component}=${it.level.name}" }
            val confidence = if (resourceIssues.any { it.level == HealthLevel.CRIT }) {
                ConfidenceLevel.HIGH
            } else {
                ConfidenceLevel.MEDIUM
            }
            return IncidentAttribution(
                kind = AttributionKind.VPS_SIDE,
                confidence = confidence,
                reason = "host resource pressure detected ($affected)",
            )
        }

        if (network?.level == HealthLevel.WARN) {
            return IncidentAttribution(
                kind = AttributionKind.USER_SIDE_OR_ROUTE,
                confidence = ConfidenceLevel.MEDIUM,
                reason = "vpn and host are up, but network quality is degraded (${network.summary})",
            )
        }

        val affected = degraded.joinToString(", ") { "${it.component}=${it.level.name}" }
        return IncidentAttribution(
            kind = AttributionKind.MIXED,
            confidence = ConfidenceLevel.LOW,
            reason = "degraded checks: $affected",
        )
    }
}
