package statusbot

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient

interface TelegramGateway {
    fun send(chatId: Long, text: String, parseMode: String? = null)
}

class TelegramApiGateway(token: String) : TelegramGateway {
    private val log = LoggerFactory.getLogger(TelegramApiGateway::class.java)
    private val client: TelegramClient = OkHttpTelegramClient(token)

    override fun send(chatId: Long, text: String, parseMode: String?) {
        val requestBuilder = SendMessage.builder()
            .chatId(chatId.toString())
            .text(text)
        if (parseMode != null) {
            requestBuilder.parseMode(parseMode)
        }
        val request = requestBuilder.build()

        try {
            client.execute(request)
        } catch (ex: TelegramApiException) {
            log.error("Telegram send failed", ex)
        }
    }
}
