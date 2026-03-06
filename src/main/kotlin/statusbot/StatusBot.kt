package statusbot

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StatusBotApp(private val settings: Settings) {
    private val log = LoggerFactory.getLogger(StatusBotApp::class.java)

    private val monitoring = MonitoringService(settings)
    private val alerting = AlertingService(
        cooldownSeconds = settings.monitorCooldownSeconds,
        recoveryEnabled = settings.monitorRecoveryEnabled,
    )
    private val bot = StatusBot(settings, monitoring, alerting)

    fun run() {
        val botsApplication = TelegramBotsLongPollingApplication()
        val shutdownHook = Thread {
            bot.shutdown()
            botsApplication.close()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            botsApplication.registerBot(settings.telegramBotToken, bot)
            bot.startMonitoring()
            log.info("StatusBot started")
            CountDownLatch(1).await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            bot.shutdown()
            botsApplication.close()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }
}

private class StatusBot(
    private val settings: Settings,
    private val monitoring: MonitoringService,
    private val alerting: AlertingService,
) : LongPollingSingleThreadUpdateConsumer {
    private val log = LoggerFactory.getLogger(StatusBot::class.java)
    private val telegramClient: TelegramClient = OkHttpTelegramClient(settings.telegramBotToken)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    override fun consume(update: Update) {
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
        val requestBuilder = SendMessage.builder()
            .chatId(chatId.toString())
            .text(text)
        if (parseMode != null) {
            requestBuilder.parseMode(parseMode)
        }
        val request = requestBuilder.build()

        try {
            telegramClient.execute(request)
        } catch (ex: TelegramApiException) {
            log.error("Telegram send failed", ex)
        }
    }
}
