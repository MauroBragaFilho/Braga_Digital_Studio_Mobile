"""Cliente HTTP do pareamento (consentimento duplo) do BDSM Link.

Fluxo (doc secao 5): POST /api/pair/request -> {requestId, code, expiresInSec};
GET /api/pair/status/{id} a cada 1-2 s ate APPROVED / DENIED / EXPIRED. O token
vem UMA unica vez na primeira consulta apos APPROVED. O token nunca e logado.
"""

import http.client
import json
import socket
import threading
import time
from dataclasses import dataclass
from typing import Callable, Optional

POLL_INTERVAL_S = 1.5
HTTP_TIMEOUT_S = 5.0


class PairingError(Exception):
    """Falha no pareamento, com mensagem pronta para o usuario."""

    def __init__(self, kind: str, message: str, http_status: Optional[int] = None):
        super().__init__(message)
        self.kind = kind          # 'network' | 'bad_request' | 'too_large' | 'rate_limited' | 'protocol' | 'cancelled'
        self.http_status = http_status


@dataclass
class PairRequestInfo:
    request_id: str
    code: str
    expires_in_sec: int


@dataclass
class PairOutcome:
    state: str                      # APPROVED | DENIED | EXPIRED
    token: Optional[str] = None
    code: Optional[str] = None


def _http(host: str, port: int, method: str, path: str, body: Optional[bytes] = None,
          token: Optional[str] = None, timeout: float = HTTP_TIMEOUT_S):
    """Faz uma requisicao e devolve (status, bytes). Erros de rede -> PairingError."""
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        headers = {"Connection": "close", "Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        conn.request(method, path, body=body, headers=headers)
        resp = conn.getresponse()
        return resp.status, resp.read(65536)
    except (OSError, http.client.HTTPException, socket.timeout) as e:
        raise PairingError("network", "Sem conexao com %s:%d (%s)" % (host, port, e))
    finally:
        conn.close()


def request_pair(host: str, port: int, client_id: str, client_name: str) -> PairRequestInfo:
    """POST /api/pair/request. Trata 400/413/429 com mensagens claras."""
    body = json.dumps({"clientId": client_id, "clientName": client_name}).encode("utf-8")
    status, data = _http(host, port, "POST", "/api/pair/request", body)
    if status == 200:
        try:
            obj = json.loads(data.decode("utf-8"))
            return PairRequestInfo(str(obj["requestId"]), str(obj["code"]), int(obj.get("expiresInSec", 90)))
        except (ValueError, KeyError, TypeError):
            raise PairingError("protocol", "Resposta de pareamento invalida do celular", status)
    if status == 400:
        raise PairingError("bad_request", "O celular rejeitou o pedido (JSON invalido, HTTP 400)", 400)
    if status == 413:
        raise PairingError("too_large", "Pedido grande demais (HTTP 413)", 413)
    if status == 429:
        raise PairingError("rate_limited",
                           "O celular esta com um pedido pendente ou recusou ha pouco; aguarde alguns segundos (HTTP 429)", 429)
    raise PairingError("protocol", "Resposta inesperada do celular: HTTP %d" % status, status)


def poll_status(host: str, port: int, request_id: str) -> PairOutcome:
    """GET /api/pair/status/{id}. PENDING devolve state='PENDING'."""
    status, data = _http(host, port, "GET", "/api/pair/status/" + request_id)
    if status == 404:
        # Pedido desconhecido (expirou/foi descartado ou veio de outro IP).
        return PairOutcome("EXPIRED")
    if status != 200:
        raise PairingError("protocol", "Resposta inesperada ao consultar o pareamento: HTTP %d" % status, status)
    try:
        obj = json.loads(data.decode("utf-8"))
        state = str(obj["state"]).upper()
    except (ValueError, KeyError, TypeError):
        raise PairingError("protocol", "Resposta de status invalida do celular", status)
    token = obj.get("token") if isinstance(obj.get("token"), str) else None
    return PairOutcome(state, token)


def pair(host: str, port: int, client_id: str, client_name: str,
         on_code: Optional[Callable[[str], None]] = None,
         cancel: Optional[threading.Event] = None,
         poll_interval: float = POLL_INTERVAL_S,
         rate_limit_delays=(6.0, 12.0, 24.0),
         extra_wait: float = 5.0) -> PairOutcome:
    """Executa o pareamento completo (bloqueante; chame de uma thread de rede).

    - 429 no pedido: espera com backoff (``rate_limit_delays``) e tenta de novo;
      esgotadas as tentativas levanta PairingError('rate_limited').
    - Polling a cada ``poll_interval`` ate APPROVED/DENIED/EXPIRED ou o prazo
      (expiresInSec + ``extra_wait``).
    """
    cancel = cancel or threading.Event()
    info = None
    attempt = 0
    while info is None:
        if cancel.is_set():
            raise PairingError("cancelled", "Pareamento cancelado")
        try:
            info = request_pair(host, port, client_id, client_name)
        except PairingError as e:
            if e.kind != "rate_limited" or attempt >= len(rate_limit_delays):
                raise
            delay = rate_limit_delays[attempt]
            attempt += 1
            if cancel.wait(delay):
                raise PairingError("cancelled", "Pareamento cancelado")
    if on_code:
        on_code(info.code)
    deadline = time.monotonic() + info.expires_in_sec + extra_wait
    while True:
        if cancel.is_set():
            raise PairingError("cancelled", "Pareamento cancelado")
        out = poll_status(host, port, info.request_id)
        if out.state != "PENDING":
            out.code = info.code
            return out
        if time.monotonic() > deadline:
            return PairOutcome("EXPIRED", code=info.code)
        if cancel.wait(poll_interval):
            raise PairingError("cancelled", "Pareamento cancelado")


def fetch_info(host: str, port: int, token: Optional[str] = None) -> dict:
    """GET /api/discovery/info (publica; so dados minimos sem token)."""
    status, data = _http(host, port, "GET", "/api/discovery/info", token=token)
    if status != 200:
        raise PairingError("protocol", "discovery/info: HTTP %d" % status, status)
    try:
        return json.loads(data.decode("utf-8"))
    except ValueError:
        raise PairingError("protocol", "discovery/info invalido")
