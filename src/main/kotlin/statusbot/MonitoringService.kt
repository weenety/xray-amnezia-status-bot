package statusbot

import org.slf4j.LoggerFactory
import java.time.ZonedDateTime

class MonitoringService(settings: Settings) {
    private val log = LoggerFactory.getLogger(MonitoringService::class.java)
    private val network = NetworkHealthChecker()
    private val xray = XrayHealthChecker(settings)
    private val amnezia = AmneziaDockerHealthChecker(settings)
    private val system = SystemHealthChecker(settings)
    private val amneziaEnabled = settings.amneziaDockerEnabled

    @Volatile
    private var latestSnapshot: StatusSnapshot? = null

    @Synchronized
    fun collectStatus(): StatusSnapshot {
        val checks = mutableListOf<CheckResult>()
        val timings = LinkedHashMap<String, Long>(6)

        checks += measureCheck("Network", timings) { network.check() }
        checks += measureCheck("Xray", timings) { xray.check() }
        if (amneziaEnabled) {
            checks += measureCheck("AmneziaWG", timings) { amnezia.check() }
        }
        measure("System", timings) {
            checks += system.check()
        }

        val overall = checks.maxByOrNull { it.level.severity }?.level ?: HealthLevel.WARN
        val snapshot = StatusSnapshot(
            timestamp = ZonedDateTime.now(),
            overall = overall,
            checks = checks,
            timingsMs = timings,
        )

        if (overall != HealthLevel.OK) {
            val badChecks = checks
                .filter { it.level != HealthLevel.OK }
                .joinToString(" | ") { "${it.component}:${it.level.name} (${it.summary})" }
            log.warn("Health degraded: overall={}, badChecks={}, timingsMs={}", overall.name, badChecks, timings)
        } else {
            log.debug("Health OK, timingsMs={}", timings)
        }

        latestSnapshot = snapshot
        return snapshot
    }

    private inline fun measure(
        name: String,
        timings: MutableMap<String, Long>,
        block: () -> Unit,
    ) {
        val started = System.nanoTime()
        block()
        timings[name] = (System.nanoTime() - started) / 1_000_000
    }

    private inline fun measureCheck(
        name: String,
        timings: MutableMap<String, Long>,
        block: () -> CheckResult,
    ): CheckResult {
        val started = System.nanoTime()
        val result = block()
        timings[name] = (System.nanoTime() - started) / 1_000_000
        return result
    }
}
