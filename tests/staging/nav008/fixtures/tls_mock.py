#!/usr/bin/env python3
"""Self-test double for tls-check.sh (NAV-008 QA tooling only, not the product). Owner: qa-engineer.

Serves HTTPS on --https-port with the given cert/key and HTTP on --http-port answering a redirect.
    python3 tls_mock.py --cert c.pem --key k.pem --https-port 18443 --http-port 18081 --host staging.nav.test
        [--allow-tls10] [--redirect-status 308] [--alt-svc-h3]
"""
import argparse
import http.server
import ssl
import threading
import warnings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cert", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--host", required=True)
    ap.add_argument("--https-port", type=int, default=18443)
    ap.add_argument("--http-port", type=int, default=18081)
    ap.add_argument("--allow-tls10", action="store_true", help="fault: also accept TLS 1.0/1.1")
    ap.add_argument("--redirect-status", type=int, default=308)
    ap.add_argument("--alt-svc-h3", action="store_true", help="fault: advertise HTTP/3")
    a = ap.parse_args()

    class S(http.server.BaseHTTPRequestHandler):
        def log_message(self, *x):
            pass

        def do_GET(self):
            body = b'{"status":"ok"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            if a.alt_svc_h3:
                self.send_header("Alt-Svc", 'h3=":443"; ma=2592000')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    class R(http.server.BaseHTTPRequestHandler):
        def log_message(self, *x):
            pass

        def redirect(self):
            port = "" if a.https_port == 443 else f":{a.https_port}"
            self.send_response(a.redirect_status)
            self.send_header("Location", f"https://{a.host}{port}{self.path}")
            self.send_header("Content-Length", "0")
            self.end_headers()

        do_GET = do_POST = do_HEAD = redirect

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(a.cert, a.key)
    if a.allow_tls10:
        warnings.filterwarnings("ignore", category=DeprecationWarning)  # deliberate fault: TLS 1.0/1.1 accepted
        ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
        ctx.minimum_version = ssl.TLSVersion.TLSv1
    else:
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    https = http.server.ThreadingHTTPServer(("127.0.0.1", a.https_port), S)
    https.socket = ctx.wrap_socket(https.socket, server_side=True)
    http_ = http.server.ThreadingHTTPServer(("127.0.0.1", a.http_port), R)
    threading.Thread(target=http_.serve_forever, daemon=True).start()
    https.serve_forever()


if __name__ == "__main__":
    main()
