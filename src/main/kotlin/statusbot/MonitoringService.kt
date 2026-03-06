package statusbot

import java.time.ZonedDateTime

class MonitoringService(private val settings: Settings) {
    private val network = NetworkHealthChecker(settings)
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

    fun getLatestOrCollect(): StatusSnapshot {
        return latestSnapshot ?: collectStatus()
    }
}
