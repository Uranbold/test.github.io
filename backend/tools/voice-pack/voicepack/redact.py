"""Second line of defence for the token (NAV-016 AC 9, ADR-0010 §4.2).

The first line is that the token is only ever placed in one request header. Every stdout/stderr/log write of the
generator still goes through `redact()`, which replaces any registered secret value with a fixed marker, so a value
that leaked into a message (an exception text, a library warning, a traceback) never reaches a stream or a file.
"""

from __future__ import annotations

import io
import json
import sys
import threading

MARKER = "[redacted]"
_lock = threading.Lock()
_secrets: list[str] = []


def register(value: str | None) -> None:
    """Remember a secret value. Empty values are ignored (nothing to hide, and "" would match everywhere)."""
    if not value:
        return
    with _lock:
        if value not in _secrets:
            _secrets.append(value)
            _secrets.sort(key=len, reverse=True)


PROXY_ENV_NAMES = ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy")


def register_proxy_credentials(environ=None) -> None:
    """ADR-0010 §4.5: a proxy URL's credential part (`user:password@`) is treated like the token. Registers the
    userinfo, the password and, when there is no password, the user name (a token used as the user name)."""
    import os
    import urllib.parse

    environ = os.environ if environ is None else environ
    for name in PROXY_ENV_NAMES:
        value = environ.get(name) or ""
        if "@" not in value:
            continue
        candidate = value if "://" in value else "http://" + value
        try:
            u = urllib.parse.urlsplit(candidate)
            netloc = u.netloc
        except ValueError:
            netloc = candidate.split("://", 1)[-1].split("/", 1)[0]
        userinfo = netloc.rpartition("@")[0]
        if not userinfo:
            continue
        register(userinfo)
        register(urllib.parse.unquote(userinfo))
        user, sep, password = userinfo.partition(":")
        if sep and password:
            register(password)
            register(urllib.parse.unquote(password))
        elif user:
            register(user)
            register(urllib.parse.unquote(user))


def clear() -> None:
    """Forget every registered value (tests)."""
    with _lock:
        _secrets.clear()


def redact(text: str) -> str:
    with _lock:
        secrets = list(_secrets)
    for s in secrets:
        if s in text:
            text = text.replace(s, MARKER)
    return text


class RedactingStream(io.TextIOBase):
    """A text stream wrapper whose every write is redacted first."""

    def __init__(self, inner):
        super().__init__()
        self._inner = inner

    def write(self, s: str) -> int:  # type: ignore[override]
        self._inner.write(redact(s))
        return len(s)

    def flush(self) -> None:
        self._inner.flush()

    @property
    def encoding(self):  # type: ignore[override]
        return getattr(self._inner, "encoding", "utf-8")

    def isatty(self) -> bool:
        return bool(getattr(self._inner, "isatty", lambda: False)())


def install() -> None:
    """Wrap sys.stdout and sys.stderr (idempotent)."""
    if not isinstance(sys.stdout, RedactingStream):
        sys.stdout = RedactingStream(sys.stdout)
    if not isinstance(sys.stderr, RedactingStream):
        sys.stderr = RedactingStream(sys.stderr)


class Reporter:
    """Human lines on stdout/stderr plus optional JSON-lines events in a log file, all redacted.

    The log holds no coordinates, user or device IDs (there are none in this tool), no token and no host name.
    """

    def __init__(self, log_path: str | None = None, quiet: bool = False):
        self.quiet = quiet
        self._log = open(log_path, "a", encoding="utf-8") if log_path else None
        self._lock = threading.Lock()

    def _event(self, level: str, msg: str, fields: dict) -> None:
        if self._log is None:
            return
        rec = {"level": level, "msg": msg, **fields}
        line = redact(json.dumps(rec, ensure_ascii=False, sort_keys=True))
        with self._lock:
            self._log.write(line + "\n")
            self._log.flush()

    def info(self, msg: str, **fields) -> None:
        if not self.quiet:
            print(redact(msg), file=sys.stdout, flush=True)
        self._event("info", msg, fields)

    def warn(self, msg: str, **fields) -> None:
        print(redact("WARNING: " + msg), file=sys.stderr, flush=True)
        self._event("warn", msg, fields)

    def error(self, msg: str, **fields) -> None:
        print(redact("ERROR: " + msg), file=sys.stderr, flush=True)
        self._event("error", msg, fields)

    def close(self) -> None:
        if self._log is not None:
            self._log.close()
            self._log = None


SECRET_ENV_NAMES = ("CHIMEGE_API_TOKEN",)


def child_env() -> dict:
    """Environment for every subprocess the generator starts (ffmpeg, git): this process's environment without the
    token (security VP-6; the token is also removed from os.environ once it has been read)."""
    import os

    return {k: v for k, v in os.environ.items() if k not in SECRET_ENV_NAMES}
