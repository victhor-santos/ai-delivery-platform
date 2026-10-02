import uvicorn

from app.config import Settings
from app.main import create_app


def main() -> None:
    settings = Settings()
    uvicorn.run(create_app(), host=settings.host, port=settings.port, log_level=settings.log_level)


if __name__ == "__main__":
    main()
