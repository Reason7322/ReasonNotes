"""Foreground-only, LAN-scoped, HMAC-authenticated deletion-fence exchange.

Never handles note content. No process or socket exists when ReasonNotes closes.
The phone is the client and polls/pushes while its application is foregrounded.
"""
from __future__ import annotations

from hashlib import sha256
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import secrets
import socket
import threading
import time
from uuid import UUID

from .repository import Repository

PORT = 49233
MAX_BYTES = 262144
TIME_WINDOW_SECONDS = 300


def shared_key(path: Path) -> bytes:
    """Create a device-private 256-bit pairing secret once, mode 0600."""
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("xb", buffering=0) as handle:
            os.fchmod(handle.fileno(), 0o600)
            key = secrets.token_bytes(32)
            handle.write(key.hex().encode("ascii") + b"\n")
            os.fsync(handle.fileno())
    except FileExistsError:
        pass
    if path.stat().st_mode & 0o077:
        raise PermissionError("pairing key has unsafe permissions")
    raw = path.read_text(encoding="ascii").strip()
    if len(raw) != 64:
        raise ValueError("invalid pairing key length")
    return bytes.fromhex(raw)


def signature(key: bytes, method: str, path: str, when: str, nonce: str, data: bytes) -> str:
    payload = "\n".join((method, path, when, nonce, sha256(data).hexdigest()))
    return hmac.new(key, payload.encode("ascii"), sha256).hexdigest()


def decode_note_ids(data: bytes) -> frozenset[UUID]:
    if len(data) > MAX_BYTES:
        raise ValueError("request exceeds deletion sync limit")
    result: set[UUID] = set()
    for value in data.decode("ascii").splitlines():
        if not value:
            continue
        parsed = UUID(value)
        if value != str(parsed):
            raise ValueError("noncanonical note ID")
        result.add(parsed)
    return frozenset(result)


def encode_note_ids(ids) -> bytes:
    return ("".join(f"{value}\n" for value in sorted(ids, key=str))).encode("ascii")


class PurgeRelay:
    def __init__(self, repository: Repository, key: bytes, host="0.0.0.0", port=PORT):
        if len(key) != 32:
            raise ValueError("pairing key must be exactly 256 bits")
        self.repository = repository
        self.key = key
        self.host = host
        self.port = port
        self._server = None
        self._thread = None
        self._nonces: dict[str, float] = {}
        self._nonce_lock = threading.Lock()
        self._changes = 0

    @property
    def changes(self):
        return self._changes

    def start(self):
        if self._server is not None:
            raise RuntimeError("relay already running")
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass  # Never log pairing/authentication headers.

            def _reply(self, status: int, body=b"", *, signed=None):
                self.send_response(status)
                self.send_header("Content-Type", "text/plain; charset=ascii")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                if signed is not None:
                    self.send_header("X-RN-Response-Sig", signed)
                self.end_headers()
                self.wfile.write(body)

            def _handle(self, method):
                if self.path != "/v1/purges":
                    self._reply(404)
                    return
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    self._reply(400)
                    return
                if size < 0 or size > MAX_BYTES or (method == "GET" and size):
                    self._reply(413)
                    return
                data = self.rfile.read(size) if size else b""
                when = self.headers.get("X-RN-Time", "")
                nonce = self.headers.get("X-RN-Nonce", "")
                sig = self.headers.get("X-RN-Sig", "")
                try:
                    timestamp = int(when)
                except ValueError:
                    self._reply(401)
                    return
                now = time.time()
                if not (0 < abs(timestamp - now) <= TIME_WINDOW_SECONDS or timestamp == int(now)):
                    self._reply(401)
                    return
                if len(nonce) < 16 or len(nonce) > 128 or len(sig) != 64:
                    self._reply(401)
                    return
                expected = signature(outer.key, method, self.path, when, nonce, data)
                if not hmac.compare_digest(expected, sig):
                    self._reply(401)
                    return
                with outer._nonce_lock:
                    outer._nonces = {n: t for n, t in outer._nonces.items()
                                     if now - t <= TIME_WINDOW_SECONDS}
                    if nonce in outer._nonces:
                        self._reply(409)
                        return
                    outer._nonces[nonce] = now
                if method == "POST":
                    try:
                        requested = decode_note_ids(data)
                        current = outer.repository.purged_ids()
                        for note_id in sorted(requested - current, key=str):
                            outer.repository.purge_note(note_id)
                        outer._changes += len(requested - current)
                    except (ValueError, RuntimeError, OSError):
                        self._reply(422)
                        return
                output = encode_note_ids(outer.repository.purged_ids())
                if len(output) > MAX_BYTES:
                    self._reply(507)
                    return
                response_sig = signature(
                    outer.key, "RESPONSE", self.path, when, nonce, output
                )
                self._reply(200, output, signed=response_sig)

            def do_GET(self):
                self._handle("GET")

            def do_POST(self):
                self._handle("POST")

        self._server = ThreadingHTTPServer((self.host, self.port), Handler)
        self.port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever,
                                        kwargs={"poll_interval": .2}, daemon=True,
                                        name="ReasonNotes-foreground-purge")
        self._thread.start()

    def stop(self):
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            if self._thread is not None:
                self._thread.join(timeout=3)
            self._thread = None
            self._server = None
