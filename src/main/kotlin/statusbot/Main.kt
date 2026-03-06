package statusbot

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("statusbot.main")

fun main() {
    val settings = Settings.fromEnv()
    settings.validate()

    log.info("Starting StatusBot")
    val app = StatusBotApp(settings)
    app.run()
}
