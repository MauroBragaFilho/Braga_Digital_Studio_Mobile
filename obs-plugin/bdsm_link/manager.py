"""Gerenciador de dispositivos BDSM: uma thread de rede por celular.

As threads NUNCA tocam a API do OBS: comunicam-se por uma fila thread-safe
(``DeviceManager.events``) que a thread do OBS esvazia em ``poll()`` (chamado
pelo timer de ~500 ms do script). Este modulo e testavel em Python puro.
"""

import json
import queue
import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple

from . import pairing
from .pairing import PairingError
from .state import LinkState
from .storage import SettingsStore
from .tally import BatteryAlert
from .websocket import ConnectionClosed, HandshakeError, WebSocketClient, WebSocketError

DEFAULT_PORT = 8080

# Estados de conexao (strings estaveis, usadas pela UI e pelos testes)
ST_DISCONNECTED = "disconnected"   # Desconectado (tentando reconectar)
ST_UNPAIRED = "unpaired"           # Sem token
ST_PENDING = "pending"             # Aguardando aprovacao no celular
ST_CONNECTING = "connecting"
ST_CONNECTED = "connected"
ST_ERROR = "error"

STATUS_LABELS = {
    ST_DISCONNECTED: "Desconectado",
    ST_UNPAIRED: "Nao pareado",
    ST_PENDING: "Aguardando aprovacao no celular",
    ST_CONNECTING: "Conectando...",
    ST_CONNECTED: "Conectado",
    ST_ERROR: "Erro",
}


def parse_endpoint(text: str) -> Optional[Tuple[str, int]]:
    """'192.168.0.5', '192.168.0.5:8080', 'http://host:8080/' -> (host, porta) ou None."""
    s = (text or "").strip()
    for p in ("http://", "https://", "ws://", "wss://"):
        if s.lower().startswith(p):
            s = s[len(p):]
    s = s.split("/", 1)[0].strip()
    if not s:
        return None
    host, port = s, DEFAULT_PORT
    if ":" in s:
        host, _, p = s.rpartition(":")
        if not p.isdigit() or not (0 < int(p) < 65536):
            return None
        port = int(p)
    if not host or any(c.isspace() for c in host):
        return None
    return host, port


def default_client_name() -> str:
    """'OBS Studio (NOME-DO-PC)', limitado a 40 caracteres (limite do servidor)."""
    pc = socket.gethostname() or "PC"
    prefix = "OBS Studio ("
    return prefix + pc[:40 - len(prefix) - 1] + ")"


class Backoff:
    """Backoff exponencial 1, 2, 4, ... ate ``cap`` (30 s)."""

    def __init__(self, base: float = 1.0, cap: float = 30.0, factor: float = 2.0):
        self.base, self.cap, self.factor = base, cap, factor
        self._n = 0

    def next(self) -> float:
        d = min(self.cap, self.base * (self.factor ** self._n))
        self._n += 1
        return d

    def reset(self) -> None:
        self._n = 0


class _Unauthorized(Exception):
    pass


class DeviceWorker(threading.Thread):
    """Thread de rede de UM celular: pareia sob demanda, mantem o WebSocket,
    reconecta com backoff e envia TALLY_UPDATE quando muda (e ao reconectar)."""

    def __init__(self, key: str, host: str, port: int, store: SettingsStore,
                 client_name: str, emit: Callable[[str, str, object], None],
                 auto_repair: bool = True, backoff: Optional[Backoff] = None,
                 poll_interval: float = pairing.POLL_INTERVAL_S,
                 rate_limit_delays=(6.0, 12.0, 24.0), idle_timeout: float = 15.0):
        super().__init__(name="bdsm-link-" + key, daemon=True)
        self.key, self.host, self.port = key, host, port
        self.store, self.client_name, self._emit = store, client_name, emit
        self.auto_repair = auto_repair
        self.backoff = backoff or Backoff()
        self.poll_interval, self.rate_limit_delays = poll_interval, rate_limit_delays
        self.idle_timeout = idle_timeout
        self._stop_ev = threading.Event()
        self._wake = threading.Event()
        self._pair_req = threading.Event()
        self._pair_cancel = threading.Event()
        self._drop = threading.Event()
        self._desired: Optional[str] = None
        self._sent: Optional[str] = None
        self._last_status = None
        self._lock = threading.Lock()

    # -- comandos (chamados da thread do OBS) --
    def request_pair(self) -> None:
        self._pair_cancel.clear()
        self._pair_req.set()
        self._wake.set()

    def forget(self) -> None:
        self._pair_cancel.set()
        self.store.clear_token(self.key)
        self._last_status = None
        self._drop.set()
        self._wake.set()

    def set_tally(self, state: str) -> None:
        with self._lock:
            self._desired = state

    def stop(self) -> None:
        self._stop_ev.set()
        self._pair_cancel.set()
        self._wake.set()

    # -- internos --
    def _status(self, st: str, detail: str = "") -> None:
        if (st, detail) == self._last_status:
            return  # evita inundar a fila com o mesmo estado
        self._last_status = (st, detail)
        self._emit(self.key, "status", (st, detail))

    def run(self) -> None:
        try:
            self._loop()
        except Exception as e:  # nunca derrubar o OBS: apenas registra
            self._emit(self.key, "log", "Erro inesperado na thread de rede: %r" % (e,))
            self._status(ST_ERROR, "Erro interno: %s" % e)

    def _loop(self) -> None:
        while not self._stop_ev.is_set():
            token = self.store.get_token(self.key)
            if not token:
                self._drop.clear()
                # Mantem a mensagem de ERRO (ex.: recusado) ate o usuario tentar de novo.
                if not self._last_status or self._last_status[0] != ST_ERROR:
                    self._status(ST_UNPAIRED, "Clique em 'Parear' e aprove no celular")
                self._wake.wait(0.5)
                self._wake.clear()
                if self._pair_req.is_set() and not self._stop_ev.is_set():
                    self._pair_req.clear()
                    self._do_pair()
                continue
            try:
                self._session(token)
                if self._stop_ev.is_set() or self._drop.is_set():
                    continue
                delay = self.backoff.next()
                self._status(ST_DISCONNECTED, "Reconectando em %.0f s" % delay)
            except _Unauthorized:
                self.store.clear_token(self.key)
                self._emit(self.key, "token_invalid", None)
                self._emit(self.key, "log", "Token recusado (401) em %s: token apagado" % self.key)
                if self.auto_repair and not self._stop_ev.is_set():
                    self._pair_req.set()
                    self._status(ST_UNPAIRED, "Token recusado; repareando")
                else:
                    self._status(ST_UNPAIRED, "Token recusado (401): clique em 'Parear'")
                continue
            except Exception as e:
                delay = self.backoff.next()
                self._status(ST_DISCONNECTED, "%s; nova tentativa em %.0f s" % (_short(e), delay))
            self._stop_ev.wait(delay)

    def _do_pair(self) -> None:
        self._status(ST_PENDING, "Enviando pedido...")
        try:
            def on_code(code: str) -> None:
                self._emit(self.key, "pair_code", code)
                self._status(ST_PENDING, code)

            out = pairing.pair(self.host, self.port, self.store.client_id, self.client_name,
                               on_code=on_code, cancel=self._pair_cancel,
                               poll_interval=self.poll_interval,
                               rate_limit_delays=self.rate_limit_delays)
        except PairingError as e:
            if e.kind == "cancelled":
                self._status(ST_UNPAIRED, "Pareamento cancelado")
            else:
                self._status(ST_ERROR, str(e))
            self._emit(self.key, "log", "Pareamento com %s falhou: %s" % (self.key, e))
            return
        if out.state == "APPROVED" and out.token:
            self.store.set_token(self.key, out.token)
            self.backoff.reset()
            self._emit(self.key, "log", "Pareado com %s" % self.key)
        elif out.state == "APPROVED":
            self._status(ST_ERROR, "Aprovado, mas o token nao foi entregue (ja consumido). Pareie de novo.")
        elif out.state == "DENIED":
            self._status(ST_ERROR, "Pedido recusado no celular")
        else:
            self._status(ST_ERROR, "Pedido expirou (90 s) sem aprovacao")

    def _session(self, token: str) -> None:
        ws = WebSocketClient(self.host, self.port, query={"token": token})
        self._status(ST_CONNECTING, "")
        try:
            ws.connect()
        except HandshakeError as e:
            if e.status == 401:
                raise _Unauthorized()
            raise
        self._sent = None  # (re)conectou: reenviar o tally atual
        self.backoff.reset()
        self._status(ST_CONNECTED, "")
        last_rx = time.monotonic()
        try:
            while not self._stop_ev.is_set() and not self._drop.is_set():
                try:
                    msg = ws.recv_message(0.2)
                except ConnectionClosed as e:
                    if e.code == 1008:  # "Access revoked"
                        raise _Unauthorized()
                    raise
                now = time.monotonic()
                if msg is not None:
                    last_rx = now
                    try:
                        self._emit(self.key, "state", LinkState.from_json(msg))
                    except ValueError:
                        pass  # mensagem estranha: ignora
                elif now - last_rx > self.idle_timeout:
                    raise WebSocketError("sem dados ha %.0f s" % self.idle_timeout)
                with self._lock:
                    want = self._desired
                if want is not None and want != self._sent:
                    ws.send_text(json.dumps({"type": "TALLY_UPDATE", "state": want}))
                    self._sent = want
        finally:
            ws.close()


def _short(e: Exception) -> str:
    s = str(e) or e.__class__.__name__
    return s if len(s) < 120 else s[:117] + "..."


@dataclass
class Device:
    key: str
    host: str
    port: int
    mapping: str = ""                       # fonte do OBS escolhida manualmente ("" = automatico)
    status: str = ST_DISCONNECTED
    detail: str = ""
    pair_code: str = ""
    state: Optional[LinkState] = None
    tally: str = "OFF"                      # ultimo tally calculado
    battery: BatteryAlert = field(default_factory=BatteryAlert)
    worker: Optional[DeviceWorker] = field(default=None, repr=False)

    @property
    def display_name(self) -> str:
        return self.state.device_name if self.state else self.key

    @property
    def ndi_stream_name(self) -> Optional[str]:
        return self.state.ndi_stream_name if self.state else None

    def status_text(self) -> str:
        label = STATUS_LABELS.get(self.status, self.status)
        if self.status == ST_PENDING and self.pair_code:
            return "%s - codigo %s" % (label, self.pair_code)
        if self.detail and self.status in (ST_ERROR, ST_DISCONNECTED, ST_UNPAIRED):
            return "%s: %s" % (label, self.detail)
        return label


@dataclass
class PollResult:
    ndi_changed: bool = False
    notices: List[str] = field(default_factory=list)


class DeviceManager:
    def __init__(self, store: SettingsStore, client_name: Optional[str] = None,
                 log: Callable[[str], None] = lambda m: None, auto_repair: bool = True,
                 worker_options: Optional[dict] = None):
        self.store = store
        self.client_name = client_name or default_client_name()
        self.log = log
        self.auto_repair = auto_repair
        self.worker_options = worker_options or {}
        self.devices: Dict[str, Device] = {}
        self.events: "queue.Queue" = queue.Queue(maxsize=2000)

    # -- chamado pelas threads --
    def _emit(self, key: str, kind: str, payload) -> None:
        try:
            self.events.put_nowait((key, kind, payload))
        except queue.Full:
            if kind != "state":  # estados sao descartaveis; o resto nao
                try:
                    self.events.get_nowait()
                    self.events.put_nowait((key, kind, payload))
                except (queue.Empty, queue.Full):
                    pass

    # -- gestao (thread do OBS) --
    def sync_devices(self, entries: List[str]) -> List[str]:
        """Reconcilia a lista de 'IP[:porta]'. Devolve as entradas invalidas."""
        wanted: Dict[str, Tuple[str, int]] = {}
        invalid: List[str] = []
        for e in entries:
            if not (e or "").strip():
                continue
            ep = parse_endpoint(e)
            if ep is None:
                invalid.append(e)
                continue
            wanted["%s:%d" % ep] = ep
        for key in [k for k in self.devices if k not in wanted]:
            self._remove(key)
        for key, (host, port) in wanted.items():
            if key not in self.devices:
                self._add(key, host, port)
        return invalid

    def _add(self, key: str, host: str, port: int) -> None:
        d = Device(key=key, host=host, port=port, mapping=self.store.get_meta(key, "mapping", "") or "")
        w = DeviceWorker(key, host, port, self.store, self.client_name, self._emit,
                         auto_repair=self.auto_repair, **self.worker_options)
        d.worker = w
        self.devices[key] = d
        w.start()

    def _remove(self, key: str) -> None:
        d = self.devices.pop(key, None)
        if d and d.worker:
            d.worker.stop()

    def set_mapping(self, key: str, source_name: str) -> None:
        d = self.devices.get(key)
        if d and d.mapping != source_name:
            d.mapping = source_name
            self.store.set_meta(key, "mapping", source_name)

    def pair(self, key: str) -> None:
        d = self.devices.get(key)
        if d and d.worker:
            d.pair_code = ""
            d.worker.request_pair()

    def forget(self, key: str) -> None:
        d = self.devices.get(key)
        if d and d.worker:
            d.worker.forget()
            d.state, d.pair_code = None, ""

    def apply_tally(self, computed: Dict[str, str]) -> None:
        for key, st in computed.items():
            d = self.devices.get(key)
            if d:
                d.tally = st
                if d.worker:
                    d.worker.set_tally(st)

    def poll(self) -> PollResult:
        """Esvazia a fila de eventos (thread do OBS). Nunca bloqueia."""
        res = PollResult()
        for _ in range(500):
            try:
                key, kind, payload = self.events.get_nowait()
            except queue.Empty:
                break
            d = self.devices.get(key)
            if d is None:
                continue
            if kind == "status":
                st, detail = payload
                if st != ST_PENDING:
                    d.pair_code = ""
                d.status, d.detail = st, detail
                if st in (ST_UNPAIRED, ST_ERROR):
                    d.state = None
            elif kind == "pair_code":
                d.pair_code = payload
                # O codigo tambem vai para o log, para conferir com o celular.
                self.log("Codigo de pareamento para %s: %s (confira no celular e aprove)" % (key, payload))
                res.notices.append("Pareamento %s: codigo %s" % (key, payload))
            elif kind == "state":
                # O 1o quadro pode vir antes do coletor do app preencher a telemetria
                # (deviceName padrao, fonte "--"): nao e dado real, ignora.
                if payload.capture_source == "--" and payload.battery_level == 0:
                    continue
                old = d.ndi_stream_name
                d.state = payload
                if payload.ndi_stream_name != old:
                    res.ndi_changed = True
                if d.battery.update(payload.battery_level):
                    msg = "Bateria baixa em %s: %d%%" % (d.display_name, payload.battery_level)
                    self.log(msg)
                    res.notices.append(msg)
            elif kind == "token_invalid":
                d.state = None
                res.notices.append("Pareamento de %s foi revogado/recusado (401)" % key)
            elif kind == "log":
                self.log(str(payload))
        return res

    def summary(self) -> str:
        if not self.devices:
            return "Nenhum celular configurado."
        conn = sum(1 for d in self.devices.values() if d.status == ST_CONNECTED)
        return "%d celular(es), %d conectado(s)" % (len(self.devices), conn)

    def shutdown(self, join_timeout: float = 2.0) -> None:
        for d in list(self.devices.values()):
            if d.worker:
                d.worker.stop()
        end = time.monotonic() + join_timeout
        for d in list(self.devices.values()):
            if d.worker:
                d.worker.join(max(0.0, end - time.monotonic()))
        self.devices.clear()
