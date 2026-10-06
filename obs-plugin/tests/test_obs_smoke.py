"""Smoke: importa bdsm_link_obs.py com um ``obspython`` FALSO (stub) e valida carga,
propriedades, update, SceneGraph real (com contagem de referencias) e unload.
NAO substitui o teste no OBS real."""
import importlib
import os
import shutil
import sys
import tempfile
import types
import unittest
from unittest.mock import MagicMock

import _setup  # noqa: F401


class Data(dict):
    """obs_data_t falso."""


class Src:
    def __init__(self, name, sid, settings=None, scene=None):
        self.name, self.id, self.settings, self.scene = name, sid, settings or {}, scene  # scene: lista de Item


class Item:
    def __init__(self, src, visible=True, group=None):
        self.src, self.visible, self.group = src, visible, group  # group: lista de Item (grupo)


class FakeObs(types.ModuleType):
    """Modulo obspython falso: o que o script usa de forma funcional + MagicMock p/ o resto."""

    LIVE = 0

    def __init__(self):
        super().__init__("obspython")
        self.sources = {}
        self.program = None
        self.preview = None
        self.studio = False
        self.logs = []
        self.timers = []
        self.event_cbs = []
        for n in ("LOG_INFO", "LOG_WARNING", "LOG_ERROR"):
            setattr(self, n, 0)
        for n in ("SCENE_CHANGED", "PREVIEW_SCENE_CHANGED", "STUDIO_MODE_ENABLED", "STUDIO_MODE_DISABLED",
                  "SCENE_COLLECTION_CHANGED", "FINISHED_LOADING"):
            setattr(self, "OBS_FRONTEND_EVENT_" + n, 100 + len(n))

    def __getattr__(self, name):  # constantes e funcoes de UI nao relevantes
        if name.startswith("__"):
            raise AttributeError(name)
        m = MagicMock(name=name)
        setattr(self, name, m)
        return m

    # log / timers / eventos
    def blog(self, level, msg):
        self.logs.append(msg)

    def timer_add(self, cb, ms):
        self.timers.append(cb)

    def timer_remove(self, cb):
        self.timers.remove(cb)

    def obs_frontend_add_event_callback(self, cb):
        self.event_cbs.append(cb)

    def obs_frontend_remove_event_callback(self, cb):
        self.event_cbs.remove(cb)

    # obs_data
    def obs_data_get_array(self, s, k):
        return s.get(k)

    def obs_data_array_count(self, a):
        return len(a)

    def obs_data_array_item(self, a, i):
        return a[i]

    def obs_data_array_create(self):
        return []

    def obs_data_create(self):
        return Data()

    def obs_data_set_string(self, d, k, v):
        d[k] = v

    def obs_data_array_push_back(self, a, item):
        a.append(dict(item))

    def obs_data_set_array(self, s, k, a):
        s[k] = a

    def obs_data_get_string(self, d, k):
        return d.get(k, "")

    def obs_data_get_bool(self, d, k):
        return bool(d.get(k, False))

    def obs_data_array_release(self, a):
        pass

    def obs_data_release(self, d):
        if isinstance(d, Data) and getattr(d, "_live", False):
            FakeObs.LIVE -= 1

    def obs_data_set_default_bool(self, s, k, v):
        s.setdefault(k, v)

    # fontes / cenas (com contagem de referencias)
    def _ref(self, src):
        if src is not None:
            FakeObs.LIVE += 1
        return src

    def obs_get_source_by_name(self, name):
        return self._ref(self.sources.get(name))

    def obs_source_release(self, src):
        FakeObs.LIVE -= 1

    def obs_frontend_get_current_scene(self):
        return self._ref(self.program)

    def obs_frontend_get_current_preview_scene(self):
        return self._ref(self.preview)

    def obs_frontend_preview_program_mode_active(self):
        return self.studio

    def obs_source_get_name(self, src):
        return src.name

    def obs_scene_from_source(self, src):
        return src.scene if src.id == "scene" else None

    def obs_scene_enum_items(self, scene):
        FakeObs.LIVE += 1
        return list(scene)

    def sceneitem_list_release(self, lst):
        FakeObs.LIVE -= 1

    def obs_sceneitem_visible(self, item):
        return item.visible

    def obs_sceneitem_is_group(self, item):
        return item.group is not None

    def obs_sceneitem_group_get_scene(self, item):
        return item.group

    def obs_sceneitem_get_source(self, item):
        return item.src

    def obs_source_get_unversioned_id(self, src):
        return src.id

    def obs_source_get_settings(self, src):
        d = Data(src.settings)
        d._live = True
        FakeObs.LIVE += 1
        return d

    def obs_enum_sources(self):
        FakeObs.LIVE += 1
        return list(self.sources.values())

    def source_list_release(self, lst):
        FakeObs.LIVE -= 1


def build_model(obs):
    cam1 = Src("Cam A51", "ndi_source", {"ndi_source_name": "BDSM (Galaxy A51)"})
    cam2 = Src("Cam S20", "ndi_source", {"ndi_source_name": "BDSM (Galaxy S20 FE)"})
    logo = Src("Logo", "image_source")
    hidden = Src("Cam Oculta", "ndi_source", {"ndi_source_name": "BDSM (Escondida)"})
    scene_s20 = Src("Cena S20", "scene", scene=[Item(cam2)])
    group_children = [Item(cam1), Item(hidden, visible=False)]
    scene_a = Src("Cena A51 em grupo", "scene", scene=[Item(logo), Item(None, group=group_children)])
    scene_main = Src("Mestre", "scene", scene=[Item(scene_s20)])
    for s in (cam1, cam2, logo, hidden, scene_s20, scene_a, scene_main):
        obs.sources[s.name] = s
    return scene_a, scene_main


class ObsSmokeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["BDSM_LINK_CONFIG_DIR"] = self.tmp
        self.obs = FakeObs()
        FakeObs.LIVE = 0
        sys.modules["obspython"] = self.obs
        sys.modules.pop("bdsm_link_obs", None)
        self.script = importlib.import_module("bdsm_link_obs")

    def tearDown(self):
        try:
            self.script.script_unload()
        finally:
            sys.modules.pop("obspython", None)
            sys.modules.pop("bdsm_link_obs", None)
            os.environ.pop("BDSM_LINK_CONFIG_DIR", None)
            shutil.rmtree(self.tmp, ignore_errors=True)

    def test_loads_and_lifecycle(self):
        s, o = self.script, self.obs
        self.assertIn("BDSM Link", s.script_description())
        settings = {}
        s.script_defaults(settings)
        self.assertTrue(settings["auto_repair"])
        s.script_load(settings)
        self.assertEqual(len(o.timers), 1)
        self.assertEqual(len(o.event_cbs), 1)
        s.script_update(settings)
        s.script_properties()
        s.script_save(settings)
        s._tick()
        s.script_unload()
        self.assertEqual((o.timers, o.event_cbs), ([], []))
        self.assertTrue(any("Script carregado" in m for m in o.logs))
        self.assertFalse(any("Falha" in m for m in o.logs), o.logs)

    def test_update_with_devices_and_properties(self):
        s = self.script
        settings = {"auto_repair": True, "devices": [{"value": "127.0.0.1:1"}, {"value": "invalido:xx"}],
                    "map_127_0_0_1_1": "Cam A51"}
        s.script_load(settings)
        s.script_update(settings)
        self.assertEqual(list(s._mgr.devices), ["127.0.0.1:1"])
        self.assertEqual(s._mgr.devices["127.0.0.1:1"].mapping, "Cam A51")
        s.script_properties()           # nao levanta com dispositivos na lista
        s._tick()
        self.assertTrue(any("invalida" in m for m in self.obs.logs))
        # botoes
        s._make_pair_cb("127.0.0.1:1")(None, None)
        s._make_forget_cb("127.0.0.1:1")(None, None)
        self.assertTrue(s._cb_refresh(None, None))
        # remover o dispositivo da lista o descarta
        settings["devices"] = []
        s.script_update(settings)
        self.assertEqual(s._mgr.devices, {})

    def test_scene_graph_and_tally_without_leaking_refs(self):
        s, o = self.script, self.obs
        scene_a, scene_main = build_model(o)
        g = s.ObsSceneGraph()
        o.program, o.preview = scene_a, scene_main
        self.assertEqual(g.program_scene(), "Cena A51 em grupo")
        self.assertIsNone(g.preview_scene())            # fora do Modo Estudio
        o.studio = True
        self.assertEqual(g.preview_scene(), "Mestre")
        # grupo expandido, item invisivel ignorado
        self.assertEqual(g.scene_sources("Cena A51 em grupo"), ["Logo", "Cam A51"])
        self.assertTrue(g.is_scene("Cena S20"))
        self.assertFalse(g.is_scene("Cam A51"))
        self.assertFalse(g.is_scene("nao existe"))
        self.assertEqual(g.ndi_name_of("Cam A51"), "BDSM (Galaxy A51)")
        self.assertIsNone(g.ndi_name_of("Logo"))
        self.assertEqual(s.list_ndi_sources(), ["Cam A51", "Cam Oculta", "Cam S20"])

        from bdsm_link.tally import TallyTarget, compute_tally
        t = [TallyTarget("a", "Galaxy A51"), TallyTarget("s", "Galaxy S20 FE"), TallyTarget("h", "Escondida")]
        # program = grupo com A51; preview = Mestre -> Cena S20 (aninhada)
        self.assertEqual(compute_tally(g, t), {"a": "PROGRAM", "s": "PREVIEW", "h": "OFF"})
        o.studio = False
        self.assertEqual(compute_tally(g, t)["s"], "OFF")
        self.assertEqual(FakeObs.LIVE, 0, "vazamento de referencias obs_*")

    def test_event_callback_recomputes_without_crash(self):
        s, o = self.script, self.obs
        build_model(o)
        settings = {"devices": [{"value": "127.0.0.1:1"}], "auto_repair": True}
        s.script_load(settings)
        s.script_update(settings)
        o.program = o.sources["Cena S20"]
        s._on_frontend_event(o.OBS_FRONTEND_EVENT_SCENE_CHANGED)
        s._on_frontend_event(-5)  # evento irrelevante
        s._tick()
        self.assertEqual(FakeObs.LIVE, 0)

    def test_mdns_merge_adds_devices(self):
        s = self.script
        settings = {"devices": [], "auto_repair": True}
        s.script_load(settings)
        s.script_update(settings)
        s._disc_q.put([("127.0.0.1", 2, "BDSM Link")])
        s._tick()
        self.assertEqual(settings["devices"], [{"value": "127.0.0.1:2"}])
        self.assertIn("127.0.0.1:2", s._mgr.devices)


if __name__ == "__main__":
    unittest.main()
