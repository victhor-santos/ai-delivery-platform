import logging
import re
import time
import uuid
from contextvars import ContextVar

from fastapi import FastAPI, Request

HEADER = "X-Request-Id"
_VALID = re.compile(r"[A-Za-z0-9-]{8,64}")
_current: ContextVar[str] = ContextVar("request_id", default="-")
logger = logging.getLogger("app.requests")


class RequestIdLogFilter(logging.Filter):
    """Adds the id of the request being handled, or "-", to every log record."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = _current.get()
        return True


def configure_logging(level: str) -> None:
    handler = logging.StreamHandler()
    handler.addFilter(RequestIdLogFilter())
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)s [%(request_id)s] %(name)s: %(message)s")
    )
    root = logging.getLogger()
    root.addHandler(handler)
    root.setLevel(logging.getLevelNamesMapping().get(level.upper(), logging.INFO))


def resolve(candidate: str | None) -> str:
    """Keeps a caller id only when it matches the gateway contract, so logs cannot be injected."""
    if candidate is not None and _VALID.fullmatch(candidate):
        return candidate
    return str(uuid.uuid4())


def request_id_of(request: Request) -> str | None:
    return getattr(request.state, "request_id", None)


def register_request_ids(app: FastAPI) -> None:
    @app.middleware("http")
    async def correlate(request: Request, call_next):
        request_id = resolve(request.headers.get(HEADER))
        request.state.request_id = request_id
        token = _current.set(request_id)
        started = time.perf_counter()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            response.headers[HEADER] = request_id
            return response
        finally:
            if request.url.path != "/health":
                logger.info(
                    "method=%s path=%s status=%s durationMs=%d",
                    request.method,
                    request.url.path,
                    status,
                    (time.perf_counter() - started) * 1000,
                )
            _current.reset(token)
