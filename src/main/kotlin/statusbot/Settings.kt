package statusbot

data class Settings(
    val telegramBotToken: String,
    val telegramAdminChatId: Long,
    val monitorEnabled: Boolean,
    val monitorIntervalSeconds: Int,
    val monitorCooldownSeconds: Int,
    val monitorRecoveryEnabled: Boolean,
    val xrayServiceName: String,
    val xrayCommandTimeoutSeconds: Int,
    val amneziaDockerEnabled: Boolean,
    val amneziaContainerName: String,
    val amneziaDockerTimeoutSeconds: Int,
    val thresholdCpuPercent: Double,
    val thresholdRamPercent: Double,
    val thresholdDiskPercent: Double,
) {
    fun validate() {
        require(telegramBotToken.isNotBlank()) { "TELEGRAM_BOT_TOKEN is required" }
        require(telegramAdminChatId != 0L) { "TELEGRAM_ADMIN_CHAT_ID is required" }
    }

    companion object {
        private const val DEFAULT_MONITOR_ENABLED = true
        private const val DEFAULT_MONITOR_INTERVAL_SECONDS = 60
        private const val DEFAULT_MONITOR_COOLDOWN_SECONDS = 300
        private const val DEFAULT_MONITOR_RECOVERY_ENABLED = true
        private const val DEFAULT_XRAY_SERVICE_NAME = "xray"
        private const val DEFAULT_XRAY_COMMAND_TIMEOUT_SECONDS = 5
        private const val DEFAULT_AMNEZIA_DOCKER_ENABLED = true
        private const val DEFAULT_AMNEZIA_CONTAINER_NAME = "amnezia-awg"
        private const val DEFAULT_AMNEZIA_DOCKER_TIMEOUT_SECONDS = 5
        private const val DEFAULT_THRESHOLD_CPU_PERCENT = 85.0
        private const val DEFAULT_THRESHOLD_RAM_PERCENT = 90.0
        private const val DEFAULT_THRESHOLD_DISK_PERCENT = 90.0

        fun fromEnv(): Settings {
            val token = System.getenv("TELEGRAM_BOT_TOKEN")?.trim().orEmpty()
            val adminChatId = System.getenv("TELEGRAM_ADMIN_CHAT_ID")?.toLongOrNull() ?: 0L
            return Settings(
                telegramBotToken = token,
                telegramAdminChatId = adminChatId,
                monitorEnabled = DEFAULT_MONITOR_ENABLED,
                monitorIntervalSeconds = DEFAULT_MONITOR_INTERVAL_SECONDS,
                monitorCooldownSeconds = DEFAULT_MONITOR_COOLDOWN_SECONDS,
                monitorRecoveryEnabled = DEFAULT_MONITOR_RECOVERY_ENABLED,
                xrayServiceName = DEFAULT_XRAY_SERVICE_NAME,
                xrayCommandTimeoutSeconds = DEFAULT_XRAY_COMMAND_TIMEOUT_SECONDS,
                amneziaDockerEnabled = DEFAULT_AMNEZIA_DOCKER_ENABLED,
                amneziaContainerName = DEFAULT_AMNEZIA_CONTAINER_NAME,
                amneziaDockerTimeoutSeconds = DEFAULT_AMNEZIA_DOCKER_TIMEOUT_SECONDS,
                thresholdCpuPercent = DEFAULT_THRESHOLD_CPU_PERCENT,
                thresholdRamPercent = DEFAULT_THRESHOLD_RAM_PERCENT,
                thresholdDiskPercent = DEFAULT_THRESHOLD_DISK_PERCENT,
            )
        }
    }
}
