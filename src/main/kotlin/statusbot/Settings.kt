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
    val thresholdCpuPercent: Double,
    val thresholdRamPercent: Double,
    val thresholdDiskPercent: Double,
) {
    fun validate() {
        require(telegramBotToken.isNotBlank()) { "TELEGRAM_BOT_TOKEN is required" }
        require(telegramAdminChatId != 0L) { "TELEGRAM_ADMIN_CHAT_ID is required" }
    }

    companion object {
        fun fromEnv(): Settings {
            val token = System.getenv("TELEGRAM_BOT_TOKEN")?.trim().orEmpty()
            val adminChatId = envLong("TELEGRAM_ADMIN_CHAT_ID", 0L)
            return Settings(
                telegramBotToken = token,
                telegramAdminChatId = adminChatId,
                monitorEnabled = envBool("MONITOR_ENABLED", true),
                monitorIntervalSeconds = maxOf(5, envInt("MONITOR_INTERVAL_SECONDS", 60)),
                monitorCooldownSeconds = maxOf(1, envInt("MONITOR_COOLDOWN_SECONDS", 300)),
                monitorRecoveryEnabled = envBool("MONITOR_RECOVERY_ENABLED", true),
                xrayServiceName = System.getenv("XRAY_SERVICE_NAME")?.trim().orEmpty().ifBlank { "xray" },
                xrayCommandTimeoutSeconds = maxOf(1, envInt("XRAY_COMMAND_TIMEOUT_SECONDS", 5)),
                thresholdCpuPercent = envDouble("THRESHOLD_CPU_PERCENT", 85.0),
                thresholdRamPercent = envDouble("THRESHOLD_RAM_PERCENT", 90.0),
                thresholdDiskPercent = envDouble("THRESHOLD_DISK_PERCENT", 90.0),
            )
        }

        private fun envBool(name: String, default: Boolean): Boolean {
            val raw = System.getenv(name) ?: return default
            return when (raw.trim().lowercase()) {
                "1", "true", "yes", "on" -> true
                else -> false
            }
        }

        private fun envInt(name: String, default: Int): Int {
            val raw = System.getenv(name) ?: return default
            return raw.toIntOrNull() ?: default
        }

        private fun envLong(name: String, default: Long): Long {
            val raw = System.getenv(name) ?: return default
            return raw.toLongOrNull() ?: default
        }

        private fun envDouble(name: String, default: Double): Double {
            val raw = System.getenv(name) ?: return default
            return raw.toDoubleOrNull() ?: default
        }
    }
}
