"""Chimege v1.2 client, build time only (ADR-0010 §4.1, §4.2, §4.5; NAV-016 AC 6, 8, 9, 11, 54; task B3).

Our own summary of the request, no vendor document text (D46):
  POST {base}/synthesize, body = the request text in UTF-8,
  headers: token, voice-id, speed 1, pitch 1, sample-rate 22050, and CONTENT_TYPE below.

Token rules: read only from the environment variable CHIMEGE_API_TOKEN, once, and then removed from this process's
environment so no child process (ffmpeg, git, processing workers) inherits it (security VP-6); sent only in the
`token` header; never in a URL, repr, exception text or log line; registered with redact.py as the second line of
defence. Redirects are never followed (a redirect would carry the header to another place).

Transport: the standard library's proxy handling (HTTPS_PROXY) and the default verified TLS context, which honours
SSL_CERT_FILE (a proxy's CA bundle is ADDED that way; verification is never turned off, ADR-0010 §4.5). Each attempt
has one overall deadline (TIMEOUT_S, monotonic clock) and a response size cap; either overrun is a retryable timeout
(security VP-2). A TLS certificate failure fails at once (VP-7).

A run with --api-base (loopback only, i.e. a fake server) is marked: manifest source.vendor/apiVersion = stub, cache
vendor = fake (pack-format §8.3, security VP-5).
"""

from __future__ import annotations

import http.client
import os
import re
import socket
import ssl
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
VENDOR = "chimege"
# pack-format §8.3: what a fake-server (--api-base) run writes and keys its cache with
FAKE_MANIFEST_VENDOR = "stub"
FAKE_MANIFEST_API_VERSION = "stub"
FAKE_CACHE_VENDOR = "fake"
# The body's content type, exactly as the F4 pre-screen sent it (88/88 requests -> 200 audio/x-wav, PCM 16-bit mono
# 22,050 Hz; confirmed by the orchestrator 2026-10-13). It is the vendor samples' own non-standard label. One constant.
CONTENT_TYPE = "plain/text"
SPEED = "1"
PITCH = "1"
TIMEOUT_S = 30.0  # one overall deadline per attempt (connect, headers and body)
MAX_RESPONSE_BYTES = 8 * 1024 * 1024  # a 300-character clip is well under 1 MB at 22,050 Hz 16-bit mono
READ_CHUNK = 64 * 1024
MAX_IN_FLIGHT = 2
RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})
BACKOFF_S = (1, 2, 4)  # waits before retry 1, 2, 3
MAX_RETRIES = 3
RETRY_AFTER_CAP_S = 60
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})
ERROR_CODE_RE = re.compile(r"\A[0-9A-Za-z-]{1,16}\Z")  # anything else is printed as "invalid Error-Code" (VP-7)

# Error-Code meanings: our wording of the ADR-0010 §4.1 tables.
TOKEN_ERROR_CODES = {"1000": "invalid", "1001": "missing", "1002": "inactive", "1003": "suspended"}
REQUEST_ERROR_CODES = {
    "4000": "the request body was not received",
    "4001": "the text after vendor normalisation is shorter than 2 characters",
    "4002": "the text after vendor normalisation is longer than 300 characters",
    "4003": "the voice-id is not a valid voice",
    "4004": "the speed is invalid",
    "4005": "special characters (the text holds characters the vendor does not allow)",
    "4006": "the text contains a series of upper-case letters",
    "4007": "the sample-rate is invalid",
    "4008": "the pitch is invalid",
}


def shown_error_code(raw: str) -> str:
    """The Error-Code header as printed: the raw value only if it is a short token of [0-9A-Za-z-] (VP-7)."""
    if not raw:
        return "none"
    return raw if ERROR_CODE_RE.match(raw) else "invalid Error-Code"


def request_error_meaning(raw: str) -> str:
    if not raw:
        return "unknown Error-Code (none sent)"
    if not ERROR_CODE_RE.match(raw):
        return "unknown Error-Code (invalid value)"
    return REQUEST_ERROR_CODES.get(raw, f"unknown Error-Code {raw}")


# ----------------------------------------------------------------------------------------------- transport
_tls = threading.local()


class _AttemptSockets:
    """Sockets opened during one attempt, so a watchdog can shut them down at the deadline (a slow-drip response
    resets the per-operation socket timeout on every byte; only shutting the socket down bounds the attempt)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._socks: list = []
        self.killed = False

    def add(self, sock) -> None:
        with self._lock:
            self._socks.append(sock)
            if self.killed:
                self._shutdown(sock)

    @staticmethod
    def _shutdown(sock) -> None:
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def kill(self) -> None:
        with self._lock:
            self.killed = True
            for s in self._socks:
                self._shutdown(s)


def _register_sock(conn) -> None:
    holder = getattr(_tls, "holder", None)
    if holder is not None and conn.sock is not None:
        holder.add(conn.sock)


class _HTTPConn(http.client.HTTPConnection):
    def connect(self):
        super().connect()
        _register_sock(self)


class _HTTPSConn(http.client.HTTPSConnection):
    def connect(self):
        super().connect()
        _register_sock(self)


class _HTTPHandler(urllib.request.HTTPHandler):
    def http_open(self, req):
        return self.do_open(_HTTPConn, req)


class _HTTPSHandler(urllib.request.HTTPSHandler):
    """Default verified context (ssl.create_default_context honours SSL_CERT_FILE); never an unverified one."""

    def __init__(self):
        super().__init__(context=ssl.create_default_context())

    def https_open(self, req):
        return self.do_open(_HTTPSConn, req, context=self._context)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401
        return None  # urllib then raises HTTPError(code): treated as a non-retryable failure


class _TooLarge(Exception):
    pass


def _cert_error(e: BaseException) -> bool:
    seen = 0
    while e is not None and seen < 5:
        if isinstance(e, ssl.SSLCertVerificationError):
            return True
        e = getattr(e, "reason", None) if isinstance(e, urllib.error.URLError) else (e.__cause__ or e.__context__)
        seen += 1
    return False


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


def take_token_from_environ() -> str:
    """Read CHIMEGE_API_TOKEN once, register it for redaction and remove it from os.environ (VP-6), so subprocesses
    and process-pool workers started later never inherit it. Returns "" when unset."""
    token = os.environ.pop(TOKEN_ENV, "")
    redact.register(token)
    return token


def read_token(env=None) -> str:
    env = os.environ if env is None else env
    token = env.get(TOKEN_ENV, "")
    if not token:
        raise ConfigError(f"{TOKEN_ENV} is not set")
    redact.register(token)
    if env is os.environ:
        os.environ.pop(TOKEN_ENV, None)  # VP-6: no child process inherits it
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
    speed = SPEED
    pitch = PITCH
    max_in_flight = MAX_IN_FLIGHT

    def __init__(self, api_base: str | None = None, *, env=None, timeout: float = TIMEOUT_S, sleep=time.sleep,
                 max_response_bytes: int = MAX_RESPONSE_BYTES):
        self._base, loopback = check_api_base(api_base)
        token = read_token(env)
        self._headers = lambda: {"token": token}  # the value lives only in this closure
        self._timeout = timeout
        self._max_bytes = max_response_bytes
        self._sleep = sleep
        # pack-format §8.3: a fake-server run can never produce a "chimege" pack or cache entry
        self.fake_server = loopback
        self.vendor = FAKE_MANIFEST_VENDOR if loopback else VENDOR
        self.api_version = FAKE_MANIFEST_API_VERSION if loopback else API_VERSION
        self.cache_vendor = FAKE_CACHE_VENDOR if loopback else VENDOR
        self.cache_api_version = API_VERSION
        handlers = [_NoRedirect(), _HTTPHandler(), _HTTPSHandler()]
        if loopback:
            handlers.append(urllib.request.ProxyHandler({}))  # never send loopback traffic through a proxy
        self._opener = urllib.request.build_opener(*handlers)
        self._lock = threading.Lock()
        self.requests_sent = 0
        self.aborted = threading.Event()

    def __repr__(self) -> str:
        return f"ChimegeSynthesiser(api_version={API_VERSION!r})"

    __str__ = __repr__

    # ------------------------------------------------------------------ one HTTP attempt
    def _read_body(self, resp, deadline: float) -> bytes:
        chunks, n = [], 0
        while True:
            if time.monotonic() > deadline:
                raise TimeoutError("deadline")
            b = resp.read(READ_CHUNK)
            if not b:
                return b"".join(chunks)
            n += len(b)
            if n > self._max_bytes:
                raise _TooLarge()
            chunks.append(b)

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
        holder = _AttemptSockets()
        _tls.holder = holder
        deadline = time.monotonic() + self._timeout
        watchdog = threading.Timer(self._timeout, holder.kill)
        watchdog.daemon = True
        watchdog.start()
        try:
            with self._opener.open(req, timeout=self._timeout) as resp:
                r.status = resp.status
                r.body = self._read_body(resp, deadline)
                r.ok = r.status == 200
                if not r.ok:
                    r.what = f"HTTP {r.status}"
        except urllib.error.HTTPError as e:
            r.status = e.code
            r.error_code = (e.headers.get("Error-Code") or "").strip() if e.headers else ""
            ra = (e.headers.get("Retry-After") or "").strip() if e.headers else ""
            if ra.isdigit() and len(ra) <= 6:
                r.retry_after = min(int(ra), RETRY_AFTER_CAP_S)
            e.close()  # the error body is never read (no size or time exposure)
            r.transient = e.code in RETRY_STATUSES
            r.what = f"HTTP {e.code}"
        except _TooLarge:
            r.transient = True
            r.what = f"timeout (response over {self._max_bytes} bytes)"
        except BaseException as e:  # noqa: BLE001 - classified below; KeyboardInterrupt re-raised
            if isinstance(e, (KeyboardInterrupt, SystemExit)):
                raise
            if _cert_error(e):
                r.transient = False
                r.what = "TLS certificate not trusted"
            elif holder.killed or isinstance(e, (socket.timeout, TimeoutError)) or (
                    isinstance(e, urllib.error.URLError) and isinstance(e.reason, (socket.timeout, TimeoutError))):
                r.transient = True
                r.what = f"timeout ({self._timeout:g} s per attempt)"
            elif isinstance(e, (urllib.error.URLError, ConnectionError, http.client.HTTPException, OSError)):
                r.transient = True
                r.what = "connection error"
            else:
                raise
        finally:
            watchdog.cancel()
            _tls.holder = None
        if holder.killed and not r.transient and r.what != "TLS certificate not trusted" and not r.ok:
            r.transient = True
            r.what = f"timeout ({self._timeout:g} s per attempt)"
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
            if r.what == "TLS certificate not trusted":
                self.aborted.set()
                raise VendorError("TLS certificate not trusted: verification stays on; behind a TLS-intercepting proxy, "
                                  "add its CA bundle with SSL_CERT_FILE (tools/voice-pack/README.md)")
            if r.status == 403:
                self.aborted.set()
                code = shown_error_code(r.error_code)
                meaning = TOKEN_ERROR_CODES.get(r.error_code, "unknown")
                raise VendorError(f"{TOKEN_ENV} rejected (code {code}: {meaning})")
            if not r.transient:
                if 400 <= r.status < 500:
                    raise VendorError(f"{who}: Chimege HTTP {r.status}, Error-Code {shown_error_code(r.error_code)}: "
                                      f"{request_error_meaning(r.error_code)}")
                raise VendorError(f"{who}: Chimege answered {r.what}")
            if attempt < MAX_RETRIES:
                wait = r.retry_after if r.retry_after is not None else BACKOFF_S[attempt]
                self._sleep(wait)
        raise VendorError(f"{who}: Chimege request failed after {1 + MAX_RETRIES} attempts (last: {last.what if last else '?'})")
