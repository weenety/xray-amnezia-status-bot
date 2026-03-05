from __future__ import annotations

import logging

from .bot import StatusBotApp
from .config import Settings


def configure_logging() -> None:
    logging.basicConfig(
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        level=logging.INFO,
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)


def main() -> None:
    configure_logging()
    settings = Settings.from_env()
    settings.validate()
    app = StatusBotApp(settings)
    app.run()


if __name__ == "__main__":
    main()
