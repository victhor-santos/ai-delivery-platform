import uvicorn

from app.api.request_ids import configure_logging
from app.config import Settings
from app.main import create_app


def main() -> None:
    settings = Settings()
    configure_logging(settings.log_level)
    # Requests are logged by the app with their request id, so the plain uvicorn access log is off.
    uvicorn.run(
        create_app(settings),
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level,
        access_log=False,
    )


if __name__ == "__main__":
    main()
