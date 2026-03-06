package statusbot

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StatusBotApp(settings: Settings) {
    private val log = LoggerFactory.getLogger(StatusBotApp::class.java)

    private val monitoring = MonitoringService(settings)
    private val alerting = AlertingService(
        cooldownSeconds = settings.monitorCooldownSeconds,
        recoveryEnabled = settings.monitorRecoveryEnabled,
    )
    private val bot = StatusBot(settings, monitoring, alerting)

    fun run() {
        val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
        botsApi.registerBot(bot)

        bot.startMonitoring()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                bot.shutdown()
            },
        )

        log.info("StatusBot started")
        CountDownLatch(1).await()
    }
}

private class StatusBot(
    private val settings: Settings,
    private val monitoring: MonitoringService,
    private val alerting: AlertingService,
) : TelegramLongPollingBot() {
    private val log = LoggerFactory.getLogger(StatusBot::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    override fun getBotToken(): String = settings.telegramBotToken

    override fun getBotUsername(): String = "statusbot"

    override fun onUpdateReceived(update: Update) {
        val message = update.message ?: return
        val text = message.text?.trim().orEmpty()
        if (text.isBlank()) {
            return
        }

        val command = text.substringBefore(' ').substringBefore('@')
        if (command != "/status") {
            return
        }

        val chatId = message.chatId
        if (chatId != settings.telegramAdminChatId) {
            safeSend(chatId, "Access denied", parseMode = null)
            return
        }

        try {
            val snapshot = monitoring.collectStatus()
            safeSend(chatId, formatStatus(snapshot), parseMode = "HTML")
        } catch (ex: Exception) {
            log.error("Failed to collect status", ex)
            safeSend(chatId, "Failed to collect status", parseMode = null)
        }
    }

    fun startMonitoring() {
        if (!settings.monitorEnabled) {
            return
        }

        scheduler.scheduleAtFixedRate(
            {
                try {
                    val snapshot = monitoring.collectStatus()
                    val events = alerting.evaluate(snapshot)
                    events.forEach { event ->
                        safeSend(settings.telegramAdminChatId, event.message, parseMode = null)
                    }
                } catch (ex: Exception) {
                    log.error("Monitoring loop failed", ex)
                }
            },
            5,
            settings.monitorIntervalSeconds.toLong(),
            TimeUnit.SECONDS,
        )
    }

    fun shutdown() {
        scheduler.shutdownNow()
    }

    private fun safeSend(chatId: Long, text: String, parseMode: String?) {
        val request = SendMessage(chatId.toString(), text)
        if (parseMode != null) {
            request.parseMode = parseMode
        }

        try {
            execute(request)
        } catch (ex: TelegramApiException) {
            log.error("Telegram send failed", ex)
        }
    }
}
