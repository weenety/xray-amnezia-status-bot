from __future__ import annotations

import logging

from telegram import Update
from telegram.constants import ParseMode
from telegram.ext import Application, CommandHandler, ContextTypes

from .alerting import AlertingService
from .config import Settings
from .formatter import format_status
from .service import MonitoringService

log = logging.getLogger(__name__)


class StatusBotApp:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.monitoring = MonitoringService(settings)
        self.alerting = AlertingService(
            cooldown_seconds=settings.monitor_cooldown_seconds,
            recovery_enabled=settings.monitor_recovery_enabled,
        )
        self.application = Application.builder().token(settings.telegram_bot_token).build()

        self.application.add_handler(CommandHandler("start", self.start_command))
        self.application.add_handler(CommandHandler("status", self.status_command))

        if settings.monitor_enabled:
            if self.application.job_queue is None:
                raise RuntimeError(
                    "JobQueue is not available. Install 'python-telegram-bot[job-queue]'."
                )
            self.application.job_queue.run_repeating(
                callback=self.monitor_job,
                interval=settings.monitor_interval_seconds,
                first=5,
                name="monitor-loop",
            )

    def run(self) -> None:
        self.application.run_polling(allowed_updates=Update.ALL_TYPES)

    async def start_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not await self._ensure_admin(update):
            return
        await self._safe_reply(update, "Use /status to get VPS status.")

    async def status_command(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not await self._ensure_admin(update):
            return
        snapshot = await self.monitoring.collect_status()
        await self._safe_reply(update, format_status(snapshot))

    async def monitor_job(self, context: ContextTypes.DEFAULT_TYPE) -> None:
        try:
            snapshot = await self.monitoring.collect_status()
            events = self.alerting.evaluate(snapshot)
            for event in events:
                await context.bot.send_message(
                    chat_id=self.settings.telegram_admin_chat_id,
                    text=event.message,
                )
        except Exception:
            log.exception("Monitoring loop failed")

    async def _ensure_admin(self, update: Update) -> bool:
        chat = update.effective_chat
        if chat is None:
            return False

        if chat.id == self.settings.telegram_admin_chat_id:
            return True

        if update.message is not None:
            await update.message.reply_text("Access denied")
        return False

    async def _safe_reply(self, update: Update, text: str) -> None:
        if update.message is not None:
            await update.message.reply_text(text, parse_mode=ParseMode.HTML)


__all__ = ["StatusBotApp"]
