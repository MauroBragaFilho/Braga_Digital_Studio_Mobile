"""mDNS minimo: consulta PTR ``_bdsm._tcp.local`` por UDP multicast e interpreta
respostas PTR/SRV/A/TXT. O app registra ``_bdsm._tcp`` via Android NSD (nome
"BDSM Link", sem TXT; o NSD acrescenta sufixo " (2)" se houver conflito).

A consulta sai de uma porta efemera (nao 5353), entao os respondedores replicam
por UNICAST (RFC 6762 secao 6.7) e nao precisamos disputar a porta 5353 com o
servico mDNS do Windows.
"""

import socket
import struct
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

MDNS_GROUP = "224.0.0.251"
MDNS_PORT = 5353
SERVICE = "_bdsm._tcp.local"

T_A, T_PTR, T_TXT, T_AAAA, T_SRV = 1, 12, 16, 28, 33


@dataclass
class ServiceInstance:
    name: str                    # ex.: "BDSM Link._bdsm._tcp.local"
    host: str = ""               # alvo do SRV, ex.: "android-1234.local"
    port: int = 0
    addresses: List[str] = field(default_factory=list)
    txt: Dict[str, str] = field(default_factory=dict)

    @property
    def label(self) -> str:
        return self.name.split("._", 1)[0]


def _encode_name(name: str) -> bytes:
    out = b""
    for label in name.strip(".").split("."):
        raw = label.encode("utf-8")
        out += bytes([len(raw)]) + raw
    return out + b"\x00"


def build_query(name: str = SERVICE, qtype: int = T_PTR, unicast: bool = False) -> bytes:
    """Pacote de consulta mDNS (1 pergunta)."""
    header = struct.pack("!HHHHHH", 0, 0, 1, 0, 0, 0)
    qclass = 1 | (0x8000 if unicast else 0)
    return header + _encode_name(name) + struct.pack("!HH", qtype, qclass)


def _read_name(data: bytes, pos: int) -> Tuple[str, int]:
    """Le um nome (com compressao). Devolve (nome, posicao apos o nome no fluxo)."""
    labels: List[str] = []
    end = -1
    jumps = 0
    while True:
        if pos >= len(data):
            raise ValueError("nome truncado")
        n = data[pos]
        if n == 0:
            pos += 1
            break
        if n & 0xC0 == 0xC0:
            if pos + 1 >= len(data):
                raise ValueError("ponteiro truncado")
            ptr = ((n & 0x3F) << 8) | data[pos + 1]
            if end < 0:
                end = pos + 2
            pos = ptr
            jumps += 1
            if jumps > 20:
                raise ValueError("laco de compressao")
            continue
        if n & 0xC0:
            raise ValueError("rotulo invalido")
        labels.append(data[pos + 1:pos + 1 + n].decode("utf-8", "replace"))
        pos += 1 + n
    return ".".join(labels), (end if end >= 0 else pos)


def parse_response(data: bytes, sender: Optional[str] = None,
                   service: str = SERVICE) -> List[ServiceInstance]:
    """Interpreta um pacote mDNS e devolve as instancias de ``service`` encontradas.
    Tolerante: pacote malformado devolve o que foi lido ate o erro."""
    instances: Dict[str, ServiceInstance] = {}
    srv: Dict[str, Tuple[str, int]] = {}
    addrs: Dict[str, List[str]] = {}
    txts: Dict[str, Dict[str, str]] = {}
    ptrs: List[str] = []
    try:
        _id, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
        pos = 12
        for _ in range(qd):
            _, pos = _read_name(data, pos)
            pos += 4
        for _ in range(an + ns + ar):
            name, pos = _read_name(data, pos)
            rtype, _rclass, _ttl, rdlen = struct.unpack("!HHIH", data[pos:pos + 10])
            pos += 10
            rd_start = pos
            rdata = data[pos:pos + rdlen]
            if len(rdata) < rdlen:
                raise ValueError("rdata truncado")
            pos += rdlen
            lname = name.lower()
            if rtype == T_PTR:
                target, _ = _read_name(data, rd_start)
                if lname == service.lower():
                    ptrs.append(target)
            elif rtype == T_SRV and rdlen >= 6:
                _prio, _w, port = struct.unpack("!HHH", rdata[:6])
                target, _ = _read_name(data, rd_start + 6)
                srv[name] = (target, port)
            elif rtype == T_A and rdlen == 4:
                addrs.setdefault(lname, []).append(socket.inet_ntoa(rdata))
            elif rtype == T_TXT:
                kv: Dict[str, str] = {}
                i = 0
                while i < len(rdata):
                    ln = rdata[i]
                    item = rdata[i + 1:i + 1 + ln].decode("utf-8", "replace")
                    i += 1 + ln
                    if item:
                        k, _, v = item.partition("=")
                        kv[k] = v
                txts[name] = kv
    except (ValueError, struct.error, IndexError):
        pass
    for inst in ptrs:
        if inst not in instances:
            instances[inst] = ServiceInstance(name=inst)
    for inst, (target, port) in srv.items():
        if inst.lower().endswith("." + service.lower()):
            si = instances.setdefault(inst, ServiceInstance(name=inst))
            si.host, si.port = target, port
    for si in instances.values():
        si.txt = txts.get(si.name, {})
        si.addresses = list(addrs.get(si.host.lower(), []))
        if not si.addresses and sender:
            si.addresses = [sender]
    return list(instances.values())


def _local_ipv4s() -> List[str]:
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return ips


def discover(timeout: float = 3.0, repeat: int = 2) -> List[ServiceInstance]:
    """Procura celulares BDSM na rede local. Bloqueante (~timeout s): chame de
    uma thread de rede. Nunca levanta; falhas devolvem lista vazia/parcial."""
    found: Dict[str, ServiceInstance] = {}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    try:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        sock.bind(("", 0))
        pkt = build_query(unicast=True)
        interfaces = _local_ipv4s() or [None]

        def send_all():
            for ip in interfaces:
                try:
                    if ip:
                        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
                    sock.sendto(pkt, (MDNS_GROUP, MDNS_PORT))
                except OSError:
                    continue

        send_all()
        sent = 1
        end = time.monotonic() + timeout
        next_send = time.monotonic() + timeout / (repeat + 1)
        while True:
            now = time.monotonic()
            if now >= end:
                break
            if sent < repeat and now >= next_send:
                send_all()
                sent += 1
                next_send = now + timeout / (repeat + 1)
            sock.settimeout(max(0.05, min(0.3, end - now)))
            try:
                data, addr = sock.recvfrom(9000)
            except socket.timeout:
                continue
            except OSError:
                break
            for si in parse_response(data, sender=addr[0]):
                prev = found.get(si.name)
                if prev is None or (si.port and not prev.port):
                    found[si.name] = si
                elif prev:
                    for a in si.addresses:
                        if a not in prev.addresses:
                            prev.addresses.append(a)
    except OSError:
        pass
    finally:
        sock.close()
    return list(found.values())


def to_endpoints(instances: List[ServiceInstance]) -> List[Tuple[str, int, str]]:
    """Converte em (ip, porta, nome) prontos para a lista de dispositivos."""
    out = []
    for si in instances:
        if si.port and si.addresses:
            out.append((si.addresses[0], si.port, si.label))
    return out
