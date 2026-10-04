#!/usr/bin/env python3
"""
A TLS server with a certificate nothing vouches for, for one test.

`core/src/iosTest/.../DarwinTrustConformanceTest` is the only test in this
project that needs a Mac: an `NSURLAuthenticationChallenge` cannot honestly be
faked, so the Darwin trust half is proved against a real handshake
(stratus-app#58). This is the other side of it.

Python and not `openssl s_server`, which was tried first: that one takes a
connection at a time and does not frame its answer, so the test that completes
a request hung behind the two whose handshake is refused.

    tls-server.py <cert.pem> <key.pem> [port]
"""

import http.server
import ssl
import sys


class AnyMethod(http.server.BaseHTTPRequestHandler):
    """Answers anything, PROPFIND included, the same way.

    What is under test is the handshake and not what is behind it, so there is
    no reason to be a WebDAV server -- only to be one that completes an
    exchange, which is what tells "the certificate was accepted" from "the
    connection hung".
    """

    protocol_version = "HTTP/1.1"

    def handle_one_request(self):
        self.raw_requestline = self.rfile.readline(65537)
        if not self.raw_requestline:
            self.close_connection = True
            return
        if not self.parse_request():
            return
        # Read the body if there is one, or the client is left writing into a
        # socket nobody drains.
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        body = b"ok"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def main() -> None:
    certificate, key = sys.argv[1], sys.argv[2]
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 18443

    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), AnyMethod)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certificate, key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
