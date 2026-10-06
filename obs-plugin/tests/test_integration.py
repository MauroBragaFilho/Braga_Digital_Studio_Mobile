"""Integracao contra o CELULAR SIMULADO (fake_phone.py): pareamento, WS, reconexao, 401, tally."""
import shutil
import tempfile
import threading
import time
import unittest

import _setup  # noqa: F401

from bdsm_link import pairing
from bdsm_link.manager import (ST_CONNECTED, ST_ERROR, ST_PENDING, ST_UNPAIRED, Backoff, DeviceManager)
from bdsm_link.pairing import PairingError
from bdsm_link.storage import SettingsStore
from bdsm_link.websocket import HandshakeError, WebSocketClient
from fake_phone import FakePhone


def wait_until(pred, timeout=8.0, step=0.02):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        v = pred()
        if v:
            return v
        time.sleep(step)
    return pred()


def later(delay, fn):
    t = threading.Timer(delay, fn)
    t.daemon = True
    t.start()
    return t


class PairingClientTests(unittest.TestCase):
    def setUp(self):
        self.phone = FakePhone(ttl_s=90, cooldown_s=0).start()

    def tearDown(self):
        self.phone.stop()

    def pair(self, **kw):
        kw.setdefault("poll_interval", 0.05)
        kw.setdefault("rate_limit_delays", (0.05, 0.05))
        return pairing.pair("127.0.0.1", self.phone.port, "obs-test-1", "OBS Studio (PC-TESTE)", **kw)

    def test_full_pairing_approved_token_once(self):
        codes = []

        def approve():
            r = self.phone.wait_for_pending()
            self.assertEqual(r["clientName"], "OBS Studio (PC-TESTE)")
            self.assertRegex(r["code"], r"^\d{4}$")
            self.phone.approve()
        later(0.2, approve)
        out = self.pair(on_code=codes.append)
        self.assertEqual(out.state, "APPROVED")
        self.assertRegex(out.token, r"^[0-9a-f]{64}$")
        self.assertEqual(codes, [out.code])
        self.assertEqual(self.phone.last_pair_body["clientId"], "obs-test-1")
        # token entregue UMA vez: a segunda consulta vem sem token
        rid = list(self.phone.requests)[0]
        again = pairing.poll_status("127.0.0.1", self.phone.port, rid)
        self.assertEqual((again.state, again.token), ("APPROVED", None))

    def test_denied(self):
        later(0.2, lambda: (self.phone.wait_for_pending(), self.phone.deny()))
        self.assertEqual(self.pair().state, "DENIED")

    def test_expired(self):
        self.phone.ttl_s = 0.5
        out = self.pair()
        self.assertEqual(out.state, "EXPIRED")

    def test_429_pending_exhausts_retries(self):
        pairing.request_pair("127.0.0.1", self.phone.port, "outro", "x")  # ocupa o unico slot do IP
        with self.assertRaises(PairingError) as cm:
            self.pair(rate_limit_delays=(0.05, 0.05))
        self.assertEqual((cm.exception.kind, cm.exception.http_status), ("rate_limited", 429))
        self.assertEqual(self.phone.pair_attempts, 4)  # 1 + 1 + 2 retentativas

    def test_429_then_recovers_after_denial(self):
        pairing.request_pair("127.0.0.1", self.phone.port, "outro", "x")
        later(0.3, lambda: self.phone.deny())            # libera o slot (cooldown 0)

        def finish():
            wait_until(lambda: len(self.phone.pending()) == 1 and self.phone.pair_attempts >= 3)
            self.phone.approve()
        later(0.4, finish)
        out = self.pair(rate_limit_delays=(0.2,) * 10)
        self.assertEqual(out.state, "APPROVED")

    def test_429_cooldown_after_denial(self):
        self.phone.cooldown_s = 30
        pairing.request_pair("127.0.0.1", self.phone.port, "a", "x")
        self.phone.deny()
        with self.assertRaises(PairingError) as cm:
            pairing.request_pair("127.0.0.1", self.phone.port, "a", "x")
        self.assertEqual(cm.exception.kind, "rate_limited")

    def test_max_three_pending(self):
        # o fake usa o IP do socket; 3 pedidos pendentes de IPs diferentes bloqueiam o 4o.
        self.phone.max_pending = 0
        with self.assertRaises(PairingError):
            pairing.request_pair("127.0.0.1", self.phone.port, "a", "x")

    def test_413_and_400_mapped(self):
        with self.assertRaises(PairingError) as cm:
            pairing.request_pair("127.0.0.1", self.phone.port, "c", "n" * 3000)
        self.assertEqual((cm.exception.kind, cm.exception.http_status), ("too_large", 413))
        status, _ = pairing._http("127.0.0.1", self.phone.port, "POST", "/api/pair/request", b"{nao json")
        self.assertEqual(status, 400)
        orig = pairing._http
        try:
            pairing._http = lambda *a, **k: (400, b"Invalid JSON")
            with self.assertRaises(PairingError) as cm:
                pairing.request_pair("h", 1, "c", "n")
            self.assertEqual(cm.exception.kind, "bad_request")
        finally:
            pairing._http = orig

    def test_network_error_is_clear(self):
        with self.assertRaises(PairingError) as cm:
            pairing.request_pair("127.0.0.1", 1, "c", "n")
        self.assertEqual(cm.exception.kind, "network")
        self.assertIn("127.0.0.1:1", str(cm.exception))

    def test_status_unknown_is_expired_and_cancel(self):
        self.assertEqual(pairing.poll_status("127.0.0.1", self.phone.port, "naoexiste").state, "EXPIRED")
        ev = threading.Event()
        later(0.2, ev.set)
        with self.assertRaises(PairingError) as cm:
            self.pair(cancel=ev)
        self.assertEqual(cm.exception.kind, "cancelled")

    def test_protected_routes_401_and_discovery_public(self):
        st, body = pairing._http("127.0.0.1", self.phone.port, "GET", "/api/media")
        self.assertEqual(st, 401)
        info = pairing.fetch_info("127.0.0.1", self.phone.port)
        self.assertTrue(info["authRequired"])
        c = WebSocketClient("127.0.0.1", self.phone.port, query={"token": "ruim"})
        with self.assertRaises(HandshakeError) as cm:
            c.connect()
        self.assertEqual(cm.exception.status, 401)


class ManagerFlowTests(unittest.TestCase):
    def setUp(self):
        self.phone = FakePhone(ttl_s=90, cooldown_s=0, ws_interval_s=0.1).start()
        self.dir = tempfile.mkdtemp()
        self.logs = []
        self.store = SettingsStore(self.dir)
        self.key = "127.0.0.1:%d" % self.phone.port
        self.mgr = None

    def tearDown(self):
        if self.mgr:
            self.mgr.shutdown()
        self.phone.stop()
        shutil.rmtree(self.dir, ignore_errors=True)

    def make(self, auto_repair=True):
        self.mgr = DeviceManager(self.store, client_name="OBS Studio (PC-TESTE)", log=self.logs.append,
                                 auto_repair=auto_repair,
                                 worker_options=dict(backoff=Backoff(0.1, 0.4), poll_interval=0.05,
                                                     rate_limit_delays=(0.1, 0.1), idle_timeout=3.0))
        self.assertEqual(self.mgr.sync_devices(["127.0.0.1:%d" % self.phone.port, "lixo:abc"]), ["lixo:abc"])
        return self.mgr

    def pump(self, pred, timeout=8.0):
        def check():
            self.mgr.poll()
            return pred()
        return wait_until(check, timeout)

    def dev(self):
        return self.mgr.devices[self.key]

    def pair_and_connect(self):
        self.make()
        self.assertTrue(self.pump(lambda: self.dev().status == ST_UNPAIRED))
        self.mgr.pair(self.key)
        r = self.phone.wait_for_pending()
        self.assertTrue(self.pump(lambda: self.dev().status == ST_PENDING and self.dev().pair_code == r["code"]))
        self.assertIn("Codigo de pareamento", "\n".join(self.logs))
        self.assertIn(r["code"], self.dev().status_text())
        self.phone.approve()
        self.assertTrue(self.pump(lambda: self.dev().status == ST_CONNECTED and self.dev().state is not None))

    def test_pair_connect_telemetry_and_tally_on_change_only(self):
        self.pair_and_connect()
        d = self.dev()
        self.assertEqual((d.state.battery_level, d.state.ndi_source_name), (88, "BDSM (Galaxy A51)"))
        tok = self.store.get_token(self.key)
        self.assertNotIn(tok, "\n".join(self.logs))  # token nunca vai ao log
        for st in ("PROGRAM", "PROGRAM", "PROGRAM", "PREVIEW", "PREVIEW", "OFF"):
            self.mgr.apply_tally({self.key: st})
            time.sleep(0.15)
        self.assertTrue(wait_until(lambda: self.phone.tally_received == ["PROGRAM", "PREVIEW", "OFF"]))
        self.assertEqual(self.phone.unmasked_frames, 0)

    def test_reconnect_after_drop_resends_tally(self):
        self.pair_and_connect()
        self.mgr.apply_tally({self.key: "PROGRAM"})
        self.assertTrue(wait_until(lambda: self.phone.tally_received == ["PROGRAM"]))
        self.phone.drop_connections()
        self.assertTrue(wait_until(lambda: self.phone.ws_connections >= 2, 10))
        self.assertTrue(wait_until(lambda: self.phone.tally_received == ["PROGRAM", "PROGRAM"], 10))
        self.assertTrue(self.pump(lambda: self.dev().status == ST_CONNECTED))

    def test_reconnects_while_phone_is_off_then_back(self):
        self.pair_and_connect()
        port = self.phone.port
        self.phone.stop()
        self.assertTrue(self.pump(lambda: self.dev().status != ST_CONNECTED))
        self.phone2 = FakePhone(ttl_s=90, ws_interval_s=0.1)
        self.phone2.tokens = dict(self.phone.tokens)
        self.phone2.start(port)
        try:
            self.assertTrue(self.pump(lambda: self.dev().status == ST_CONNECTED, 15))
        finally:
            self.phone2.stop()

    def test_401_after_revoke_repairs_automatically(self):
        self.pair_and_connect()
        old = self.store.get_token(self.key)
        self.phone.revoke_all()
        r = self.phone.wait_for_pending(8)        # reparear automatico gerou novo pedido
        self.assertEqual(self.store.get_token(self.key), None)
        self.phone.approve(r["id"])
        self.assertTrue(self.pump(lambda: self.dev().status == ST_CONNECTED and self.dev().state is not None))
        new = self.store.get_token(self.key)
        self.assertTrue(new and new != old)

    def test_401_without_auto_repair_waits_for_user(self):
        self.pair_and_connect()
        self.mgr.auto_repair = False
        self.dev().worker.auto_repair = False
        self.phone.revoke_all()
        self.assertTrue(self.pump(lambda: self.dev().status == ST_UNPAIRED and self.store.get_token(self.key) is None))
        time.sleep(0.5)
        self.assertEqual(self.phone.pending(), [])

    def test_bad_saved_token_gives_401_at_handshake(self):
        self.store.set_token(self.key, "0" * 64)
        self.make(auto_repair=False)
        self.assertTrue(self.pump(lambda: self.dev().status == ST_UNPAIRED and self.store.get_token(self.key) is None))

    def test_denied_shows_error_and_forget(self):
        self.make()
        self.assertTrue(self.pump(lambda: self.dev().status == ST_UNPAIRED))
        self.mgr.pair(self.key)
        self.phone.wait_for_pending()
        self.phone.deny()
        self.assertTrue(self.pump(lambda: self.dev().status == ST_ERROR and "recusado" in self.dev().detail))
        # pareia de novo, conecta e esquece
        self.mgr.pair(self.key)
        self.phone.wait_for_pending()
        self.phone.approve()
        self.assertTrue(self.pump(lambda: self.dev().status == ST_CONNECTED))
        self.mgr.forget(self.key)
        self.assertTrue(self.pump(lambda: self.dev().status == ST_UNPAIRED))
        self.assertIsNone(self.store.get_token(self.key))

    def test_battery_alert_through_manager(self):
        from bdsm_link.state import LinkState
        self.make()
        for lvl in (50, 19, 18, 22, 19):
            self.mgr._emit(self.key, "state", LinkState(device_name="Cel", battery_level=lvl))
        res = self.mgr.poll()
        self.assertEqual(sum("Bateria baixa" in n for n in res.notices), 1)
        self.mgr._emit(self.key, "state", LinkState(device_name="Cel", battery_level=30))
        self.mgr._emit(self.key, "state", LinkState(device_name="Cel", battery_level=10))
        res = self.mgr.poll()
        self.assertEqual(sum("Bateria baixa" in n for n in res.notices), 1)

    def test_mapping_persisted(self):
        self.make()
        self.mgr.set_mapping(self.key, "Minha Fonte")
        self.assertEqual(SettingsStore(self.dir).get_meta(self.key, "mapping"), "Minha Fonte")
        self.mgr.shutdown()
        self.mgr = DeviceManager(SettingsStore(self.dir), log=self.logs.append)
        self.mgr.sync_devices([self.key])
        self.assertEqual(self.dev().mapping, "Minha Fonte")


if __name__ == "__main__":
    unittest.main()
