"""Celular BDSM SIMULADO (HTTP + WebSocket em stdlib) que reproduz o protocolo
real do LinkServer (core-network/.../LinkModule.kt + auth/LinkAuthManager.kt):

 - GET  /api/discovery/info   : publico, dados minimos (sem token)
 - POST /api/pair/request     : 400 JSON invalido, 413 corpo > 2048, 429 anti-spam
                                (1 pendente por IP, max 3 pendentes, cooldown apos
                                recusa/expiracao); devolve {requestId, code, expiresInSec}
 - GET  /api/pair/status/{id} : PENDING/APPROVED/DENIED/EXPIRED; token UMA vez; 404 se desconhecido
 - qualquer outra /api/*      : 401 "Pairing required" sem token valido
 - WS   /ws/link?token=       : 401 sem token; envia LinkState a cada 500 ms (configuravel),
                                revalida o token a cada ciclo (fecha 1008 "Access revoked"),
                                recebe TALLY_UPDATE (texto <= 1024 chars), quadro > 8 KB fecha (1009)
"""

import base64
import hashlib
import json
import secrets
import socket
import struct
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_PAIR_BODY = 2048
WS_MAX_FRAME = 8 * 1024
WS_MAX_TEXT = 1024


class FakePhone:
    def __init__(self, ttl_s=90.0, cooldown_s=5.0, ws_interval_s=0.5, max_pending=3):
        self.ttl_s, self.cooldown_s, self.ws_interval_s = ttl_s, cooldown_s, ws_interval_s
        self.max_pending = max_pending
        self.lock = threading.RLock()
        self.requests = {}          # id -> dict
        self.tokens = {}            # token -> clientId
        self.last_rejected = {}     # ip -> t
        self.state = {
            "deviceName": "Galaxy A51", "batteryLevel": 88, "isCharging": False,
            "captureSource": "Camera", "cameraLens": "Wide", "fps": 30,
            "microphone": "Microfone interno", "isRecording": False,
            "ndiStreamName": "Galaxy A51", "tally": "OFF",
        }
        self.tally_received = []    # estados TALLY_UPDATE aceitos
        self.unmasked_frames = 0    # violacoes do cliente (deve ficar 0)
        self.ws_connections = 0     # total de WS aceitos
        self.ws_active = []         # sockets abertos (para derrubar)
        self.pair_attempts = 0      # POST /api/pair/request recebidos
        self.last_pair_body = None
        self._httpd = None
        self._thread = None

    # ---- ciclo de vida ----
    def start(self, port=0):
        phone = self

        class H(_Handler):
            fake = phone
        self._httpd = ThreadingHTTPServer(("127.0.0.1", port), H)
        self._httpd.daemon_threads = True
        self.port = self._httpd.server_address[1]
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()
        return self

    def stop(self):
        self.drop_connections()
        if self._httpd:
            self._httpd.shutdown()
            self._httpd.server_close()

    # ---- controle pelo teste ----
    def pending(self):
        with self.lock:
            self._expire()
            return [r for r in self.requests.values() if r["state"] == "PENDING"]

    def approve(self, req_id=None):
        with self.lock:
            self._expire()
            r = self._pick(req_id)
            if not r or r["state"] != "PENDING":
                return None
            token = secrets.token_hex(32)
            for t in [t for t, c in self.tokens.items() if c == r["clientId"]]:
                del self.tokens[t]      # um clientId tem um unico token
            self.tokens[token] = r["clientId"]
            r.update(state="APPROVED", token=token)
            return token

    def deny(self, req_id=None):
        with self.lock:
            r = self._pick(req_id)
            if r and r["state"] == "PENDING":
                r["state"] = "DENIED"
                self.last_rejected[r["ip"]] = time.monotonic()

    def revoke_all(self):
        with self.lock:
            self.tokens.clear()

    def drop_connections(self):
        for s in list(self.ws_active):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def wait_for_pending(self, timeout=5.0):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            p = self.pending()
            if p:
                return p[0]
            time.sleep(0.02)
        raise AssertionError("nenhum pedido de pareamento chegou")

    # ---- internos ----
    def _pick(self, req_id):
        if req_id:
            return self.requests.get(req_id)
        for r in self.requests.values():
            if r["state"] == "PENDING":
                return r
        return None

    def _expire(self):
        now = time.monotonic()
        for r in self.requests.values():
            if r["state"] == "PENDING" and now - r["created"] > self.ttl_s:
                r["state"] = "EXPIRED"
                self.last_rejected[r["ip"]] = now

    def create_request(self, client_id, client_name, ip):
        with self.lock:
            self._expire()
            now = time.monotonic()
            active = [r for r in self.requests.values() if r["state"] == "PENDING"]
            if len(active) >= self.max_pending or any(r["ip"] == ip for r in active):
                return None
            rej = self.last_rejected.get(ip)
            if rej is not None and now - rej < self.cooldown_s:
                return None
            cid = "".join(c for c in client_id if c.isalnum() or c in "-_.:")[:64] or secrets.token_hex(8)
            name = "".join(c for c in client_name if c.isprintable()).strip()[:40] or "Cliente desconhecido"
            r = {"id": secrets.token_hex(12), "clientId": cid, "clientName": name, "ip": ip,
                 "code": "%04d" % secrets.randbelow(10000), "created": now, "state": "PENDING", "token": None}
            self.requests[r["id"]] = r
            return r

    def authorized(self, token):
        if not token or len(token) > 128:
            return False
        with self.lock:
            return token in self.tokens


def _bearer(handler):
    h = handler.headers.get("Authorization")
    if h and h.lower().startswith("bearer "):
        return h[7:].strip()
    q = urllib.parse.parse_qs(urllib.parse.urlparse(handler.path).query)
    return (q.get("token") or [None])[0]


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    fake = None

    def log_message(self, *a):
        pass

    def _send(self, status, body=b"", ctype="application/json"):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        f = self.fake
        if path == "/api/discovery/info":
            if f.authorized(_bearer(self)):
                return self._send(200, json.dumps({"deviceName": "Galaxy A51", "full": True}))
            return self._send(200, json.dumps({"deviceName": "Galaxy A51", "deviceModel": "SM-A515F",
                                                "appVersion": "test", "authRequired": True}))
        if path.startswith("/api/pair/status/"):
            rid = path.rsplit("/", 1)[1]
            with f.lock:
                f._expire()
                r = f.requests.get(rid)
                if r is None or r["ip"] != self.client_address[0]:
                    return self._send(404)
                out = {"state": r["state"]}
                if r["state"] == "APPROVED" and r["token"]:
                    out["token"] = r["token"]
                    r["token"] = None          # entregue UMA unica vez
            return self._send(200, json.dumps(out))
        if path == "/ws/link":
            return self._websocket()
        if path.startswith("/api/"):
            if not f.authorized(_bearer(self)):
                return self._send(401, "Pairing required", "text/plain")
            return self._send(200, "[]")
        return self._send(404)

    def do_POST(self):
        f = self.fake
        path = urllib.parse.urlparse(self.path).path
        if path != "/api/pair/request":
            if not f.authorized(_bearer(self)):
                return self._send(401, "Pairing required", "text/plain")
            return self._send(404)
        n = int(self.headers.get("Content-Length") or 0)
        f.pair_attempts += 1
        if n > MAX_PAIR_BODY:
            return self._send(413)
        raw = self.rfile.read(n) if n else b""
        try:
            obj = json.loads(raw.decode("utf-8"))
            if not isinstance(obj, dict):
                raise ValueError
        except ValueError:
            return self._send(400, "Invalid JSON", "text/plain")
        f.last_pair_body = obj
        r = f.create_request(str(obj.get("clientId", "")), str(obj.get("clientName", "")),
                             self.client_address[0])
        if r is None:
            return self._send(429, "Pending pairing request exists", "text/plain")
        return self._send(200, json.dumps({"requestId": r["id"], "code": r["code"], "expiresInSec": 90}))

    # ---- WebSocket ----
    def _websocket(self):
        f = self.fake
        token = _bearer(self)
        if not f.authorized(token):
            return self._send(401, "Pairing required", "text/plain")
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            return self._send(400)
        accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        self.wfile.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                          "Connection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        self.wfile.flush()
        sock = self.connection
        f.ws_connections += 1
        f.ws_active.append(sock)
        self.close_connection = True
        stop = threading.Event()
        send_lock = threading.Lock()

        def send(op, payload=b""):
            n = len(payload)
            hdr = bytes([0x80 | op]) + (bytes([n]) if n < 126 else b"\x7e" + struct.pack("!H", n))
            with send_lock:
                sock.sendall(hdr + payload)

        def close(code, reason):
            try:
                send(0x8, struct.pack("!H", code) + reason.encode())
            except OSError:
                pass
            stop.set()

        def sender():
            try:
                while not stop.is_set():
                    if not f.authorized(token):
                        close(1008, "Access revoked")
                        return
                    send(0x1, json.dumps(f.state).encode())
                    stop.wait(f.ws_interval_s)
            except OSError:
                stop.set()

        t = threading.Thread(target=sender, daemon=True)
        t.start()
        buf = b""
        sock.settimeout(0.1)
        try:
            while not stop.is_set():
                try:
                    chunk = sock.recv(65536)
                    if not chunk:
                        break
                    buf += chunk
                except socket.timeout:
                    continue
                while True:
                    fr = self._parse(buf)
                    if fr is None:
                        break
                    opcode, payload, masked, used = fr
                    buf = buf[used:]
                    if not masked:
                        f.unmasked_frames += 1
                    if opcode == 0x8:
                        close(1000, "")
                        stop.set()
                    elif opcode == 0x9:
                        send(0xA, payload)
                    elif opcode == 0x1:
                        if not f.authorized(token):
                            close(1008, "Access revoked")
                            continue
                        self._handle_text(payload.decode("utf-8", "replace"))
                    if opcode == -1:
                        close(1009, "too big")
        except (OSError, ValueError):
            pass
        finally:
            stop.set()
            if sock in f.ws_active:
                f.ws_active.remove(sock)

    def _parse(self, buf):
        if len(buf) < 2:
            return None
        masked = bool(buf[1] & 0x80)
        n = buf[1] & 0x7F
        pos = 2
        if n == 126:
            if len(buf) < 4:
                return None
            n = struct.unpack("!H", buf[2:4])[0]
            pos = 4
        elif n == 127:
            if len(buf) < 10:
                return None
            n = struct.unpack("!Q", buf[2:10])[0]
            pos = 10
        if n > WS_MAX_FRAME:                       # maxFrameSize = 8 KB
            raise ValueError("frame too big")
        key = b""
        if masked:
            if len(buf) < pos + 4:
                return None
            key = buf[pos:pos + 4]
            pos += 4
        if len(buf) < pos + n:
            return None
        payload = buf[pos:pos + n]
        if masked:
            payload = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
        return buf[0] & 0x0F, payload, masked, pos + n

    def _handle_text(self, text):
        # Espelha handleClientMessage(): >1024 chars, tipo diferente ou JSON invalido -> ignora
        if len(text) > WS_MAX_TEXT:
            return
        try:
            obj = json.loads(text)
            if not isinstance(obj, dict) or obj.get("type") != "TALLY_UPDATE":
                return
            state = str(obj.get("state", "")).upper()
        except ValueError:
            return
        if state in ("OFF", "PREVIEW", "PROGRAM"):
            self.fake.tally_received.append(state)
            self.fake.state["tally"] = state
