"""Local fake of the Chimege synthesis endpoint for the generator tests (task B3). 127.0.0.1 only, never the real API.

Each POST is recorded (path, headers, UTF-8 body). The response comes from a script: `responder(n, text, headers)`
returns a Reply; the default answers 200 with the stub synthesiser's WAV for the text. The server also measures how
many requests are open at the same time (`max_in_flight`).
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from voicepack.audio import write_wav
from voicepack.synth.stub import StubSynthesiser

_stub = StubSynthesiser()


@dataclass
class Reply:
    status: int = 200
    body: bytes | None = None  # None with 200 = stub WAV of the request text
    headers: dict = field(default_factory=dict)
    delay: float = 0.0  # seconds before answering (a delay longer than the client timeout = a timeout)


@dataclass
class Recorded:
    path: str
    headers: dict
    text: str


def tiny_wav(rate: int = 22050) -> bytes:
    """A cheap valid answer for tests that stop after synthesis."""
    return write_wav([0] * 100 + [3000, -3000] * 2000 + [0] * 100, rate)


class FakeChimege:
    def __init__(self, responder=None, default_delay: float = 0.0):
        self.responder = responder
        self.default_delay = default_delay
        self.requests: list[Recorded] = []
        self._lock = threading.Lock()
        self.in_flight = 0
        self.max_in_flight = 0
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):  # keep test output clean
                pass

            def do_POST(self):
                n_bytes = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n_bytes)
                text = body.decode("utf-8", "replace")
                hdrs = {k.lower(): v for k, v in self.headers.items()}
                with outer._lock:
                    n = len(outer.requests)
                    outer.requests.append(Recorded(self.path, hdrs, text))
                    outer.in_flight += 1
                    outer.max_in_flight = max(outer.max_in_flight, outer.in_flight)
                try:
                    reply = outer.responder(n, text, hdrs) if outer.responder else Reply()
                    delay = reply.delay or outer.default_delay
                    if delay:
                        time.sleep(delay)
                    data = reply.body
                    if data is None:
                        data = _stub.synthesize(text, hdrs.get("voice-id", ""), int(hdrs.get("sample-rate", "22050"))) if reply.status == 200 else b""
                    self.send_response(reply.status)
                    ctype = "audio/x-wav" if reply.status == 200 else "text/plain"
                    self.send_header("Content-Type", ctype)
                    for k, v in reply.headers.items():
                        self.send_header(k, v)
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                finally:
                    with outer._lock:
                        outer.in_flight -= 1

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.server.block_on_close = False
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}/v1.2"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()

    def texts(self) -> list[str]:
        return [r.text for r in self.requests]
