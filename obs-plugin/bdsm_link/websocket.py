"""Cliente WebSocket minimo (RFC 6455) em stdlib.

Recursos: handshake com ``?token=``, quadros de cliente SEMPRE mascarados,
ping/pong, mensagens de texto (com fragmentacao), fechamento, limite de 8 KB
por quadro enviado (o servidor fecha a conexao acima disso) e timeouts.
Nao chama nada do OBS; roda em threads de rede.
"""

import base64
import hashlib
import os
import socket
import struct
import urllib.parse
from typing import List, Optional, Tuple

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
OP_CONT, OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA
MAX_SEND_FRAME = 8 * 1024          # limite do servidor (maxFrameSize)
MAX_RECV_MESSAGE = 256 * 1024      # defesa contra servidor malicioso


class WebSocketError(Exception):
    """Erro de protocolo/transporte."""


class HandshakeError(WebSocketError):
    """Handshake recusado; ``status`` traz o codigo HTTP (ex.: 401)."""

    def __init__(self, status: int, message: str = ""):
        super().__init__("Handshake WebSocket recusado: HTTP %d %s" % (status, message))
        self.status = status


class ConnectionClosed(WebSocketError):
    """Conexao encerrada (pelo servidor ou pela rede)."""

    def __init__(self, code: Optional[int] = None, reason: str = ""):
        super().__init__("Conexao fechada (codigo=%s, motivo=%s)" % (code, reason))
        self.code = code
        self.reason = reason


def encode_frame(opcode: int, payload: bytes = b"", mask: bool = True, fin: bool = True,
                 mask_key: Optional[bytes] = None) -> bytes:
    """Monta um quadro. Quadros de cliente DEVEM ser mascarados (mask=True)."""
    b0 = (0x80 if fin else 0) | opcode
    n = len(payload)
    mbit = 0x80 if mask else 0
    if n < 126:
        header = struct.pack("!BB", b0, mbit | n)
    elif n < 65536:
        header = struct.pack("!BBH", b0, mbit | 126, n)
    else:
        header = struct.pack("!BBQ", b0, mbit | 127, n)
    if not mask:
        return header + payload
    key = mask_key if mask_key is not None else os.urandom(4)
    masked = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
    return header + key + masked


class FrameDecoder:
    """Decodificador incremental: alimente com bytes, receba quadros completos.
    Um timeout no meio de um quadro nao corrompe o estado (o buffer e mantido)."""

    def __init__(self, max_payload: int = MAX_RECV_MESSAGE):
        self._buf = bytearray()
        self._max = max_payload

    def feed(self, data: bytes) -> List[Tuple[bool, int, bytes]]:
        self._buf += data
        frames = []
        while True:
            f = self._try_one()
            if f is None:
                break
            frames.append(f)
        return frames

    def _try_one(self):
        buf = self._buf
        if len(buf) < 2:
            return None
        b0, b1 = buf[0], buf[1]
        fin = bool(b0 & 0x80)
        if b0 & 0x70:
            raise WebSocketError("Bits RSV inesperados")
        opcode = b0 & 0x0F
        masked = bool(b1 & 0x80)
        n = b1 & 0x7F
        pos = 2
        if n == 126:
            if len(buf) < 4:
                return None
            n = struct.unpack("!H", bytes(buf[2:4]))[0]
            pos = 4
        elif n == 127:
            if len(buf) < 10:
                return None
            n = struct.unpack("!Q", bytes(buf[2:10]))[0]
            pos = 10
        if n > self._max:
            raise WebSocketError("Quadro grande demais (%d bytes)" % n)
        if opcode >= 0x8 and (n > 125 or not fin):
            raise WebSocketError("Quadro de controle invalido")
        key = b""
        if masked:
            if len(buf) < pos + 4:
                return None
            key = bytes(buf[pos:pos + 4])
            pos += 4
        if len(buf) < pos + n:
            return None
        payload = bytes(buf[pos:pos + n])
        del buf[:pos + n]
        if masked:
            payload = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
        return fin, opcode, payload


class WebSocketClient:
    """Conexao WebSocket cliente. Use em UMA thread (nao e thread-safe)."""

    def __init__(self, host: str, port: int, path: str = "/ws/link",
                 query: Optional[dict] = None, connect_timeout: float = 5.0):
        self.host, self.port = host, port
        self.path = path
        self.query = query or {}
        self.connect_timeout = connect_timeout
        self._sock: Optional[socket.socket] = None
        self._dec = FrameDecoder()
        self._pending_frames: list = []
        self._frag_op: Optional[int] = None
        self._frag = bytearray()
        self._closed = False
        self._sent_close = False

    # -- conexao --------------------------------------------------------------
    def connect(self) -> None:
        target = self.path
        if self.query:
            target += "?" + urllib.parse.urlencode(self.query)
        key = base64.b64encode(os.urandom(16)).decode()
        sock = socket.create_connection((self.host, self.port), timeout=self.connect_timeout)
        try:
            sock.settimeout(self.connect_timeout)
            req = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\n"
                   "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
                   "Sec-WebSocket-Version: 13\r\nUser-Agent: BDSM-Link-OBS\r\n\r\n"
                   % (target, self.host, self.port, key))
            sock.sendall(req.encode("ascii"))
            head = b""
            while b"\r\n\r\n" not in head:
                chunk = sock.recv(4096)
                if not chunk:
                    raise WebSocketError("Servidor fechou durante o handshake")
                head += chunk
                if len(head) > 16384:
                    raise WebSocketError("Resposta de handshake grande demais")
            head, _, rest = head.partition(b"\r\n\r\n")
            lines = head.decode("latin-1").split("\r\n")
            parts = lines[0].split(" ", 2)
            status = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
            if status != 101:
                raise HandshakeError(status, parts[2] if len(parts) > 2 else "")
            headers = {}
            for ln in lines[1:]:
                k, _, v = ln.partition(":")
                headers[k.strip().lower()] = v.strip()
            want = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
            if headers.get("sec-websocket-accept") != want:
                raise WebSocketError("Sec-WebSocket-Accept invalido")
            self._sock = sock
            self._pending_frames = self._dec.feed(rest) if rest else []
        except BaseException:
            sock.close()
            raise

    # -- envio ----------------------------------------------------------------
    def _send_frame(self, opcode: int, payload: bytes = b"") -> None:
        if self._sock is None:
            raise ConnectionClosed()
        if len(payload) > MAX_SEND_FRAME:
            raise ValueError("Quadro acima de %d bytes" % MAX_SEND_FRAME)
        try:
            self._sock.settimeout(5.0)
            self._sock.sendall(encode_frame(opcode, payload, mask=True))
        except OSError as e:
            self._mark_closed()
            raise ConnectionClosed(None, str(e))

    def send_text(self, text: str) -> None:
        self._send_frame(OP_TEXT, text.encode("utf-8"))

    def ping(self, data: bytes = b"") -> None:
        self._send_frame(OP_PING, data[:125])

    def close(self, code: int = 1000, reason: str = "") -> None:
        if self._sock is not None and not self._sent_close and not self._closed:
            self._sent_close = True
            try:
                self._sock.settimeout(1.0)
                self._sock.sendall(encode_frame(OP_CLOSE, struct.pack("!H", code) + reason.encode()[:100]))
            except OSError:
                pass
        self._mark_closed()

    def _mark_closed(self) -> None:
        self._closed = True
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    # -- recepcao -------------------------------------------------------------
    def recv_message(self, timeout: float = 0.25) -> Optional[str]:
        """Devolve o proximo texto, ``None`` se estourou o timeout sem mensagem
        completa, ou levanta ConnectionClosed. Responde ping automaticamente."""
        while True:
            while self._pending_frames:
                fin, op, payload = self._pending_frames.pop(0)
                msg = self._handle_frame(fin, op, payload)
                if msg is not None:
                    return msg
            if self._sock is None:
                raise ConnectionClosed()
            try:
                self._sock.settimeout(timeout)
                data = self._sock.recv(65536)
            except (socket.timeout, TimeoutError):
                return None
            except OSError as e:
                self._mark_closed()
                raise ConnectionClosed(None, str(e))
            if not data:
                self._mark_closed()
                raise ConnectionClosed(None, "EOF")
            try:
                self._pending_frames.extend(self._dec.feed(data))
            except WebSocketError:
                self.close(1002, "protocol error")
                raise

    def _handle_frame(self, fin: bool, op: int, payload: bytes) -> Optional[str]:
        if op == OP_PING:
            self._send_frame(OP_PONG, payload)
            return None
        if op == OP_PONG:
            return None
        if op == OP_CLOSE:
            code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else None
            reason = payload[2:].decode("utf-8", "replace") if len(payload) > 2 else ""
            if not self._sent_close and self._sock is not None:
                self._sent_close = True
                try:  # devolve o quadro de close (eco do codigo)
                    self._sock.settimeout(1.0)
                    self._sock.sendall(encode_frame(OP_CLOSE, payload[:2]))
                except OSError:
                    pass
            self._mark_closed()
            raise ConnectionClosed(code, reason)
        if op in (OP_TEXT, OP_BINARY):
            if self._frag_op is not None:
                raise WebSocketError("Nova mensagem durante fragmentacao")
            if fin:
                return payload.decode("utf-8", "replace") if op == OP_TEXT else None
            self._frag_op, self._frag = op, bytearray(payload)
            return None
        if op == OP_CONT:
            if self._frag_op is None:
                raise WebSocketError("Continuacao sem inicio")
            self._frag += payload
            if len(self._frag) > MAX_RECV_MESSAGE:
                raise WebSocketError("Mensagem grande demais")
            if fin:
                op0, data = self._frag_op, bytes(self._frag)
                self._frag_op, self._frag = None, bytearray()
                return data.decode("utf-8", "replace") if op0 == OP_TEXT else None
            return None
        raise WebSocketError("Opcode desconhecido %d" % op)
