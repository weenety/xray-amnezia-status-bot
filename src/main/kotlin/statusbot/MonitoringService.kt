package statusbot

import java.time.ZonedDateTime

class MonitoringService(settings: Settings) {
    private val network = NetworkHealthChecker()
    private val xray = XrayHealthChecker(settings)
    private val system = SystemHealthChecker(settings)

    @Volatile
    private var latestSnapshot: StatusSnapshot? = null

    @Synchronized
    fun collectStatus(): StatusSnapshot {
        val checks = mutableListOf<CheckResult>()
        checks += network.check()
        checks += xray.check()
        checks += system.check()

        val overall = checks.maxByOrNull { it.level.severity }?.level ?: HealthLevel.WARN
        val snapshot = StatusSnapshot(
            timestamp = ZonedDateTime.now(),
            overall = overall,
            checks = checks,
        )
        latestSnapshot = snapshot
        return snapshot
    }

}
