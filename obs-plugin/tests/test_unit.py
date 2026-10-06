"""Testes unitarios em Python puro: WebSocket, LinkState, storage/DPAPI, mDNS, tally, bateria."""
import json
import os
import socket
import struct
import sys
import tempfile
import unittest

import _setup  # noqa: F401

from bdsm_link import mdns, storage
from bdsm_link.manager import Backoff, parse_endpoint
from bdsm_link.state import LinkState
from bdsm_link.tally import BatteryAlert, SceneGraph, TallyTarget, compute_tally, norm
from bdsm_link.websocket import (OP_CLOSE, OP_CONT, OP_PING, OP_PONG, OP_TEXT, ConnectionClosed,
                                 FrameDecoder, WebSocketClient, WebSocketError, encode_frame)


# ------------------------------------------------------------------ WebSocket
_SOCKS = []


def tearDownModule():
    for s in _SOCKS:
        try:
            s.close()
        except OSError:
            pass


def _pair_client():
    a, b = socket.socketpair()
    _SOCKS.extend([a, b])
    c = WebSocketClient("x", 0)
    c._sock = a
    return c, b


def _read_frame(sock):
    """Le um quadro (do cliente) e devolve (fin, opcode, masked, payload)."""
    sock.settimeout(2)
    data = b""
    while True:
        data += sock.recv(65536)
        dec = FrameDecoder()
        try:
            hdr = data[:2]
            masked = bool(hdr[1] & 0x80)
            frames = dec.feed(data)
        except WebSocketError:
            continue
        if frames:
            fin, op, payload = frames[0]
            return fin, op, masked, payload


class WebSocketTests(unittest.TestCase):
    def test_client_frames_are_masked(self):
        c, srv = _pair_client()
        c.send_text('{"type":"TALLY_UPDATE","state":"PROGRAM"}')
        fin, op, masked, payload = _read_frame(srv)
        self.assertTrue(masked)
        self.assertEqual(op, OP_TEXT)
        self.assertEqual(json.loads(payload)["state"], "PROGRAM")

    def test_mask_roundtrip_with_fixed_key(self):
        raw = encode_frame(OP_TEXT, b"hello", mask=True, mask_key=b"\x01\x02\x03\x04")
        self.assertTrue(raw[1] & 0x80)
        self.assertNotIn(b"hello", raw)
        self.assertEqual(FrameDecoder().feed(raw)[0][2], b"hello")

    def test_extended_lengths(self):
        for n in (125, 126, 300, 70000):
            raw = encode_frame(OP_TEXT, b"a" * n, mask=True)
            self.assertEqual(len(FrameDecoder().feed(raw)[0][2]), n)

    def test_send_over_8kb_rejected(self):
        c, srv = _pair_client()
        with self.assertRaises(ValueError):
            c.send_text("x" * (8 * 1024 + 1))
        c.send_text("x" * (8 * 1024))  # exatamente no limite passa

    def test_ping_gets_masked_pong(self):
        c, srv = _pair_client()
        srv.sendall(encode_frame(OP_PING, b"abc", mask=False))
        self.assertIsNone(c.recv_message(0.2))
        fin, op, masked, payload = _read_frame(srv)
        self.assertEqual((op, payload, masked), (OP_PONG, b"abc", True))

    def test_text_message(self):
        c, srv = _pair_client()
        srv.sendall(encode_frame(OP_TEXT, "olá".encode(), mask=False))
        self.assertEqual(c.recv_message(1), "olá")
        self.assertIsNone(c.recv_message(0.05))  # timeout sem dados

    def test_fragmented_message_with_ping_in_between(self):
        c, srv = _pair_client()
        srv.sendall(encode_frame(OP_TEXT, b'{"a":', mask=False, fin=False))
        srv.sendall(encode_frame(OP_PING, b"", mask=False))
        srv.sendall(encode_frame(OP_CONT, b'1,', mask=False, fin=False))
        srv.sendall(encode_frame(OP_CONT, b'"b":2}', mask=False, fin=True))
        self.assertEqual(c.recv_message(1), '{"a":1,"b":2}')

    def test_partial_frame_across_timeouts_keeps_state(self):
        c, srv = _pair_client()
        raw = encode_frame(OP_TEXT, b"partido", mask=False)
        srv.sendall(raw[:3])
        self.assertIsNone(c.recv_message(0.05))
        srv.sendall(raw[3:])
        self.assertEqual(c.recv_message(1), "partido")

    def test_close_frame_is_echoed_and_raises(self):
        c, srv = _pair_client()
        srv.sendall(encode_frame(OP_CLOSE, struct.pack("!H", 1008) + b"Access revoked", mask=False))
        with self.assertRaises(ConnectionClosed) as cm:
            c.recv_message(1)
        self.assertEqual(cm.exception.code, 1008)
        self.assertEqual(cm.exception.reason, "Access revoked")
        fin, op, masked, payload = _read_frame(srv)
        self.assertEqual(op, OP_CLOSE)

    def test_eof_raises_closed(self):
        c, srv = _pair_client()
        srv.close()
        with self.assertRaises(ConnectionClosed):
            c.recv_message(1)

    def test_decoder_rejects_oversize_and_rsv(self):
        with self.assertRaises(WebSocketError):
            FrameDecoder(max_payload=10).feed(encode_frame(OP_TEXT, b"x" * 11, mask=False))
        with self.assertRaises(WebSocketError):
            FrameDecoder().feed(bytes([0xC1, 0x00]))


# ------------------------------------------------------------------ LinkState / util
class StateTests(unittest.TestCase):
    def test_full_payload(self):
        s = LinkState.from_json(json.dumps({
            "deviceName": "Galaxy S20 FE", "batteryLevel": 88, "isCharging": True,
            "captureSource": "Câmera", "cameraLens": "Ultrawide", "fps": 30,
            "microphone": "Microfone interno", "isRecording": False,
            "ndiStreamName": "Galaxy S20 FE", "tally": "PROGRAM", "campoNovo": 1}))
        self.assertEqual((s.battery_level, s.fps, s.tally), (88, 30, "PROGRAM"))
        self.assertEqual(s.ndi_source_name, "BDSM (Galaxy S20 FE)")

    def test_null_ndi_and_missing_fields(self):
        s = LinkState.from_json('{"ndiStreamName": null, "tally": "xyz", "batteryLevel": "oops"}')
        self.assertIsNone(s.ndi_stream_name)
        self.assertIsNone(s.ndi_source_name)
        self.assertEqual((s.tally, s.battery_level, s.device_name), ("OFF", 0, "BDSM Device"))

    def test_not_object(self):
        with self.assertRaises(ValueError):
            LinkState.from_json("[1,2]")

    def test_parse_endpoint(self):
        self.assertEqual(parse_endpoint("192.168.0.5"), ("192.168.0.5", 8080))
        self.assertEqual(parse_endpoint(" 192.168.0.5:9000 "), ("192.168.0.5", 9000))
        self.assertEqual(parse_endpoint("http://10.0.0.2:8080/"), ("10.0.0.2", 8080))
        self.assertIsNone(parse_endpoint("10.0.0.2:abc"))
        self.assertIsNone(parse_endpoint("10.0.0.2:70000"))
        self.assertIsNone(parse_endpoint(""))

    def test_backoff_caps_at_30(self):
        b = Backoff()
        seq = [b.next() for _ in range(8)]
        self.assertEqual(seq[:5], [1, 2, 4, 8, 16])
        self.assertEqual(seq[5:], [30, 30, 30])
        b.reset()
        self.assertEqual(b.next(), 1)


# ------------------------------------------------------------------ storage / DPAPI
class StorageTests(unittest.TestCase):
    def test_protect_roundtrip(self):
        tok = "ab" * 32
        blob = storage.protect_secret(tok)
        self.assertEqual(storage.unprotect_secret(blob), tok)
        self.assertNotIn(tok, blob)
        if sys.platform.startswith("win"):
            self.assertTrue(blob.startswith("dpapi:"))

    def test_plain_fallback_roundtrip(self):
        blob = storage.protect_secret("tok", use_dpapi=False)
        self.assertTrue(blob.startswith("plain:"))
        self.assertEqual(storage.unprotect_secret(blob), "tok")

    def test_garbage_returns_none(self):
        self.assertIsNone(storage.unprotect_secret("dpapi:@@@"))
        self.assertIsNone(storage.unprotect_secret("nada"))
        self.assertIsNone(storage.unprotect_secret("dpapi:" + "QUJD"))  # base64 valido mas nao e blob DPAPI

    def test_store_persists_and_never_writes_plain_token(self):
        with tempfile.TemporaryDirectory() as d:
            s1 = SettingsStoreFactory(d)
            cid = s1.client_id
            self.assertTrue(cid.startswith("obs-"))
            tok = "cd" * 32
            s1.set_token("1.2.3.4:8080", tok, "Cel")
            s1.set_meta("1.2.3.4:8080", "mapping", "Cam 1")
            with open(os.path.join(d, "bdsm_link.json"), encoding="utf-8") as f:
                raw = f.read()
            self.assertNotIn(tok, raw)
            s2 = SettingsStoreFactory(d)
            self.assertEqual(s2.client_id, cid)  # clientId estavel
            self.assertEqual(s2.get_token("1.2.3.4:8080"), tok)
            self.assertEqual(s2.get_meta("1.2.3.4:8080", "mapping"), "Cam 1")
            s2.clear_token("1.2.3.4:8080")
            self.assertIsNone(SettingsStoreFactory(d).get_token("1.2.3.4:8080"))

    def test_corrupted_file_recovers(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "bdsm_link.json"), "w") as f:
                f.write("{nao e json")
            s = SettingsStoreFactory(d)
            self.assertTrue(s.client_id)
            self.assertIsNone(s.get_token("x:1"))

    @unittest.skipUnless(os.name == "posix", "chmod 600 so em POSIX")
    def test_file_mode_600(self):
        with tempfile.TemporaryDirectory() as d:
            s = storage.SettingsStore(d, use_dpapi=False)
            s.set_token("a:1", "t")
            self.assertEqual(os.stat(s.path).st_mode & 0o777, 0o600)


def SettingsStoreFactory(d):
    return storage.SettingsStore(d)


# ------------------------------------------------------------------ mDNS
def _name(n):
    return mdns._encode_name(n)


def _rr(name_bytes, rtype, rdata, ttl=120):
    return name_bytes + struct.pack("!HHIH", rtype, 0x8001, ttl, len(rdata)) + rdata


def build_mdns_response(instance="BDSM Link", port=8080, ip="192.168.0.20", host="android-1a2b.local",
                        with_a=True):
    svc = "_bdsm._tcp.local"
    inst = "%s.%s" % (instance, svc)
    body = b""
    count = 0
    # PTR _bdsm._tcp.local -> instancia (nome completo; rdata usa ponteiro de compressao para o servico)
    ptr_name = _name(svc)
    inst_label = bytes([len(instance.encode())]) + instance.encode()
    # a instancia = label + ponteiro para o nome do servico (offset 12 + 0?) calculado abaixo
    header_len = 12
    ptr_rr_start = header_len
    svc_offset = ptr_rr_start  # o nome do PTR comeca logo apos o cabecalho
    rdata_ptr = inst_label + struct.pack("!H", 0xC000 | svc_offset)
    body += _rr(ptr_name, mdns.T_PTR, rdata_ptr)
    count += 1
    # SRV usa o nome da instancia (label + ponteiro)
    inst_off = header_len + len(ptr_name) + 10 + 0  # inicio do rdata do PTR
    inst_name_ptr = struct.pack("!H", 0xC000 | inst_off)
    srv_rdata = struct.pack("!HHH", 0, 0, port) + _name(host)
    body += _rr(inst_name_ptr, mdns.T_SRV, srv_rdata)
    count += 1
    txt = b"".join(bytes([len(x)]) + x for x in (b"ver=1", b"modelo=A51"))
    body += _rr(inst_name_ptr, mdns.T_TXT, txt)
    count += 1
    if with_a:
        body += _rr(_name(host), mdns.T_A, socket.inet_aton(ip))
        count += 1
    header = struct.pack("!HHHHHH", 0, 0x8400, 0, 1, 0, count - 1)
    return header + body


class MdnsTests(unittest.TestCase):
    def test_build_query(self):
        q = mdns.build_query()
        _id, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", q[:12])
        self.assertEqual((qd, an), (1, 0))
        name, pos = mdns._read_name(q, 12)
        self.assertEqual(name, "_bdsm._tcp.local")
        self.assertEqual(struct.unpack("!HH", q[pos:pos + 4]), (mdns.T_PTR, 1))
        self.assertEqual(struct.unpack("!HH", mdns.build_query(unicast=True)[-4:])[1], 0x8001)

    def test_parse_full_response(self):
        pkt = build_mdns_response()
        found = mdns.parse_response(pkt, sender="192.168.0.99")
        self.assertEqual(len(found), 1)
        si = found[0]
        self.assertEqual(si.name, "BDSM Link._bdsm._tcp.local")
        self.assertEqual(si.label, "BDSM Link")
        self.assertEqual((si.host, si.port), ("android-1a2b.local", 8080))
        self.assertEqual(si.addresses, ["192.168.0.20"])
        self.assertEqual(si.txt, {"ver": "1", "modelo": "A51"})
        self.assertEqual(mdns.to_endpoints(found), [("192.168.0.20", 8080, "BDSM Link")])

    def test_missing_a_record_falls_back_to_sender(self):
        found = mdns.parse_response(build_mdns_response(with_a=False), sender="10.1.1.7")
        self.assertEqual(found[0].addresses, ["10.1.1.7"])

    def test_other_services_ignored_and_garbage_tolerated(self):
        pkt = build_mdns_response().replace(b"_bdsm", b"_xxxx")
        self.assertEqual(mdns.parse_response(pkt), [])
        self.assertEqual(mdns.parse_response(b"\x00\x01"), [])
        self.assertEqual(mdns.parse_response(os.urandom(64)) is not None, True)
        truncated = build_mdns_response()[:40]
        mdns.parse_response(truncated)  # nao levanta

    def test_compression_loop_does_not_hang(self):
        evil = struct.pack("!HHHHHH", 0, 0x8400, 1, 0, 0, 0) + b"\xc0\x0c\x00\x01\x00\x01"
        self.assertEqual(mdns.parse_response(evil), [])


# ------------------------------------------------------------------ tally
class FakeGraph(SceneGraph):
    """scenes: {cena: [fonte...]}; grupos ja vem expandidos (como no grafo real);
    ndi: {fonte: ndi_source_name}."""

    def __init__(self, scenes, ndi, program, preview=None, hidden=()):
        self.scenes, self.ndi, self.program, self.preview = scenes, ndi, program, preview
        self.hidden = set(hidden)

    def program_scene(self):
        return self.program

    def preview_scene(self):
        return self.preview

    def scene_sources(self, scene):
        return [s for s in self.scenes.get(scene, []) if (scene, s) not in self.hidden]

    def is_scene(self, source):
        return source in self.scenes

    def ndi_name_of(self, source):
        return self.ndi.get(source)


class TallyTests(unittest.TestCase):
    A51 = TallyTarget("a51", "Galaxy A51")
    S20 = TallyTarget("s20", "Galaxy S20 FE")

    def graph(self, **kw):
        scenes = {"Cena A51": ["Cam A51"], "Cena S20": ["Cam S20"], "Texto": ["Logo"]}
        ndi = {"Cam A51": "BDSM (Galaxy A51)", "Cam S20": "BDSM (Galaxy S20 FE)"}
        scenes.update(kw.pop("scenes", {}))
        ndi.update(kw.pop("ndi", {}))
        return FakeGraph(scenes, ndi, **kw)

    def test_simple_scene_program(self):
        r = compute_tally(self.graph(program="Cena A51"), [self.A51, self.S20])
        self.assertEqual(r, {"a51": "PROGRAM", "s20": "OFF"})

    def test_studio_mode_preview_and_program(self):
        r = compute_tally(self.graph(program="Cena A51", preview="Cena S20"), [self.A51, self.S20])
        self.assertEqual(r, {"a51": "PROGRAM", "s20": "PREVIEW"})

    def test_studio_mode_off_means_no_preview(self):
        r = compute_tally(self.graph(program="Cena A51", preview=None), [self.S20])
        self.assertEqual(r["s20"], "OFF")

    def test_program_beats_preview(self):
        r = compute_tally(self.graph(program="Cena A51", preview="Cena A51"), [self.A51])
        self.assertEqual(r["a51"], "PROGRAM")

    def test_nested_scene_and_cycle(self):
        g = self.graph(program="Mestre", scenes={"Mestre": ["Cena A51", "Logo"], "Cena A51": ["Cam A51", "Mestre"]})
        self.assertEqual(compute_tally(g, [self.A51, self.S20]), {"a51": "PROGRAM", "s20": "OFF"})

    def test_group_expanded_sources(self):
        g = self.graph(program="Com grupo", scenes={"Com grupo": ["Logo", "Cam S20"]})  # filho do grupo ja vem plano
        self.assertEqual(compute_tally(g, [self.S20])["s20"], "PROGRAM")

    def test_hidden_item_does_not_count(self):
        g = self.graph(program="Cena A51", hidden=[("Cena A51", "Cam A51")])
        self.assertEqual(compute_tally(g, [self.A51])["a51"], "OFF")

    def test_case_and_whitespace_tolerant(self):
        g = self.graph(program="Cena A51", ndi={"Cam A51": "  bdsm  (GALAXY   a51) "})
        self.assertEqual(compute_tally(g, [self.A51])["a51"], "PROGRAM")
        self.assertEqual(norm("  A  B "), "a b")

    def test_machine_prefix_tolerance(self):
        g = self.graph(program="Cena A51", ndi={"Cam A51": "OUTRO-PC (Galaxy A51)"})
        self.assertEqual(compute_tally(g, [self.A51])["a51"], "PROGRAM")

    def test_ndi_off_never_matches(self):
        g = self.graph(program="Cena A51")
        self.assertEqual(compute_tally(g, [TallyTarget("x", None)])["x"], "OFF")

    def test_no_false_positive_on_prefix(self):
        g = self.graph(program="Cena A51", ndi={"Cam A51": "BDSM (Galaxy A510)"})
        self.assertEqual(compute_tally(g, [self.A51])["a51"], "OFF")

    def test_manual_mapping(self):
        g = self.graph(program="Cena S20", ndi={"Cam S20": "QUALQUER (outro nome)"})
        auto = TallyTarget("s20", "Galaxy S20 FE")
        manual = TallyTarget("s20", "Galaxy S20 FE", mapping="cam s20")
        self.assertEqual(compute_tally(g, [auto])["s20"], "OFF")  # nome NDI diferente: so o mapeamento resolve
        self.assertEqual(compute_tally(g, [manual])["s20"], "PROGRAM")

    def test_manual_mapping_overrides_auto(self):
        # mapeado para a fonte do outro celular: ignora o NDI proprio
        g = self.graph(program="Cena A51")
        t = TallyTarget("s20", "Galaxy S20 FE", mapping="Cam A51")
        self.assertEqual(compute_tally(g, [t])["s20"], "PROGRAM")
        g2 = self.graph(program="Cena S20")
        self.assertEqual(compute_tally(g2, [t])["s20"], "OFF")

    def test_two_phones_independent(self):
        g = self.graph(program="Cena S20", preview="Cena A51")
        self.assertEqual(compute_tally(g, [self.A51, self.S20]), {"a51": "PREVIEW", "s20": "PROGRAM"})

    def test_none_program_scene(self):
        g = self.graph(program=None)
        self.assertEqual(compute_tally(g, [self.A51])["a51"], "OFF")


class BatteryTests(unittest.TestCase):
    def test_alert_once_with_hysteresis(self):
        b = BatteryAlert()
        seq = [50, 21, 20, 19, 18, 19, 20, 22, 19, 18]
        fired = [b.update(x) for x in seq]
        self.assertEqual(fired, [False, False, False, True, False, False, False, False, False, False])
        self.assertFalse(b.update(24))
        self.assertFalse(b.update(25))   # rearma ao atingir 25
        self.assertTrue(b.update(19))    # nova descida: alerta de novo
        self.assertFalse(b.update(10))


if __name__ == "__main__":
    unittest.main()
