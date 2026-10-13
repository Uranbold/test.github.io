"""Chimege v1.2 client, build time only (ADR-0010 §4.1, §4.2; NAV-016 AC 6, 8, 9, 11, 54; task B3).

Our own summary of the request, no vendor document text (D46):
  POST {base}/synthesize, body = the request text in UTF-8,
  headers: token, voice-id, speed 1, pitch 1, sample-rate 22050, and CONTENT_TYPE below.

Token rules: read only from the environment variable CHIMEGE_API_TOKEN; sent only in the `token` header; never in a
URL, repr, exception text or log line; registered with redact.py as the second line of defence. Redirects are never
followed (a redirect would carry the header to another place).
"""

from __future__ import annotations

import http.client
import os
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from .. import redact
from ..audio import read_wav, require_pcm16_mono
from ..errors import ConfigError, VendorError

TOKEN_ENV = "CHIMEGE_API_TOKEN"
API_BASE = "https://api.chimege.com/v1.2"
API_VERSION = "v1.2"
# The body's content type, exactly as the F4 pre-screen sent it (88/88 requests -> 200 audio/x-wav, PCM 16-bit mono
# 22,050 Hz; confirmed by the orchestrator 2026-10-13). It is the vendor samples' own non-standard label. One constant.
CONTENT_TYPE = "plain/text"
SPEED = "1"
PITCH = "1"
TIMEOUT_S = 30.0
MAX_IN_FLIGHT = 2
RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})
BACKOFF_S = (1, 2, 4)  # waits before retry 1, 2, 3
MAX_RETRIES = 3
RETRY_AFTER_CAP_S = 60
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})

# 403 Error-Code meanings, in the order ADR-0010 §4.1 lists them ("invalid, missing, inactive or suspended"); the
# task file confirms 1001 = missing.
TOKEN_ERROR_CODES = {"1000": "invalid", "1001": "missing", "1002": "inactive", "1003": "suspended"}
# 400 Error-Code meanings. Only 4005 is confirmed in the repository (task file B3); the architect's summary
# (ADR-0010 §4.1) lists the other causes without numbers, so they are reported generically until it adds them.
REQUEST_ERROR_CODES = {"4005": "special characters (the text holds characters the vendor does not allow)"}
REQUEST_ERROR_GENERIC = ("request rejected (one of: body not received, text shorter than 2 or longer than 300 characters, "
                         "unknown voice, speed, sample rate or pitch, disallowed characters, a run of upper-case letters)")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401
        return None  # urllib then raises HTTPError(code): treated as a non-retryable failure


def check_api_base(api_base: str | None) -> tuple[str, bool]:
    """Return (base, is_loopback). An override is accepted only for a loopback host (the fake server)."""
    if api_base is None:
        return API_BASE, False
    try:
        u = urllib.parse.urlsplit(api_base)
        host = u.hostname
    except ValueError:
        host = None
        u = None
    if u is None or u.scheme not in ("http", "https") or host not in LOOPBACK_HOSTS or u.username or u.password:
        raise ConfigError("--api-base is accepted only for a loopback host (127.0.0.1, ::1, localhost)")
    return api_base.rstrip("/"), True


def read_token(env=None) -> str:
    env = os.environ if env is None else env
    token = env.get(TOKEN_ENV, "")
    if not token:
        raise ConfigError(f"{TOKEN_ENV} is not set")
    redact.register(token)
    return token


class _Attempt:
    __slots__ = ("ok", "body", "status", "error_code", "retry_after", "transient", "what")

    def __init__(self):
        self.ok = False
        self.body = b""
        self.status = 0
        self.error_code = ""
        self.retry_after = None
        self.transient = False
        self.what = ""


class ChimegeSynthesiser:
    vendor = "chimege"
    api_version = API_VERSION
    speed = SPEED
    pitch = PITCH
    max_in_flight = MAX_IN_FLIGHT

    def __init__(self, api_base: str | None = None, *, env=None, timeout: float = TIMEOUT_S, sleep=time.sleep):
        self._base, loopback = check_api_base(api_base)
        token = read_token(env)
        self._headers = lambda: {"token": token}  # the value lives only in this closure
        self._timeout = timeout
        self._sleep = sleep
        handlers = [_NoRedirect()]
        if loopback:
            handlers.append(urllib.request.ProxyHandler({}))  # never send loopback traffic through a proxy
        self._opener = urllib.request.build_opener(*handlers)
        self._lock = threading.Lock()
        self.requests_sent = 0
        self.aborted = threading.Event()

    def __repr__(self) -> str:
        return f"ChimegeSynthesiser(api_version={self.api_version!r})"

    __str__ = __repr__

    # ------------------------------------------------------------------ one HTTP attempt
    def _once(self, text: str, voice: str, sample_rate: int) -> _Attempt:
        r = _Attempt()
        headers = {
            "Content-Type": CONTENT_TYPE,
            "voice-id": voice,
            "speed": SPEED,
            "pitch": PITCH,
            "sample-rate": str(sample_rate),
            **self._headers(),
        }
        req = urllib.request.Request(self._base + "/synthesize", data=text.encode("utf-8"), headers=headers, method="POST")
        with self._lock:
            self.requests_sent += 1
        try:
            with self._opener.open(req, timeout=self._timeout) as resp:
                r.status = resp.status
                r.body = resp.read()
                r.ok = r.status == 200
                if not r.ok:
                    r.what = f"HTTP {r.status}"
        except urllib.error.HTTPError as e:
            r.status = e.code
            r.error_code = (e.headers.get("Error-Code") or "").strip() if e.headers else ""
            ra = (e.headers.get("Retry-After") or "").strip() if e.headers else ""
            if ra.isdigit():
                r.retry_after = min(int(ra), RETRY_AFTER_CAP_S)
            try:
                e.read()
            except Exception:
                pass
            e.close()
            r.transient = e.code in RETRY_STATUSES
            r.what = f"HTTP {e.code}"
        except (socket.timeout, TimeoutError):
            r.transient = True
            r.what = "timeout"
        except urllib.error.URLError as e:
            r.transient = True
            r.what = "timeout" if isinstance(e.reason, (socket.timeout, TimeoutError)) else "connection error"
        except (ConnectionError, http.client.HTTPException, OSError):
            r.transient = True
            r.what = "connection error"
        return r

    # ------------------------------------------------------------------ retry policy
    def synthesize(self, request_text: str, voice: str, sample_rate: int, *, label: str = "") -> bytes:
        who = label or "entry"
        last = None
        for attempt in range(1 + MAX_RETRIES):
            if self.aborted.is_set():
                raise VendorError(f"{who}: run aborted after an earlier vendor error")
            r = self._once(request_text, voice, sample_rate)
            if r.ok:
                try:
                    require_pcm16_mono(read_wav(r.body), sample_rate)
                except ValueError as e:
                    raise VendorError(f"{who}: Chimege returned 200 but not a PCM 16-bit mono {sample_rate} Hz WAV ({e})") from None
                return r.body
            last = r
            if r.status == 403:
                self.aborted.set()
                meaning = TOKEN_ERROR_CODES.get(r.error_code, "unknown")
                code = r.error_code or "none"
                raise VendorError(f"{TOKEN_ENV} rejected (code {code}: {meaning})")
            if not r.transient:
                if 400 <= r.status < 500:
                    meaning = REQUEST_ERROR_CODES.get(r.error_code, REQUEST_ERROR_GENERIC) if r.error_code else "no Error-Code header"
                    raise VendorError(f"{who}: Chimege HTTP {r.status}, Error-Code {r.error_code or 'none'}: {meaning}")
                raise VendorError(f"{who}: Chimege answered {r.what}")
            if attempt < MAX_RETRIES:
                wait = r.retry_after if r.retry_after is not None else BACKOFF_S[attempt]
                self._sleep(wait)
        raise VendorError(f"{who}: Chimege request failed after {1 + MAX_RETRIES} attempts (last: {last.what if last else '?'})")
