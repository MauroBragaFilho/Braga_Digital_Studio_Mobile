"""BDSM Link para OBS Studio (script Python).

Carregue em OBS > Ferramentas > Scripts > "+" . Pareia com celulares BDSM,
mostra a telemetria (bateria, lente, fonte, fps, REC) e envia o TALLY_UPDATE
(PROGRAM / PREVIEW / OFF) conforme as cenas do OBS.

Arquitetura:
  * pacote ``bdsm_link/`` (ao lado deste arquivo): rede, pareamento, tally;
    NAO depende do obspython e e testado em Python puro.
  * ESTE arquivo e a unica camada que fala com o ``obspython``.
  * threads de rede NUNCA chamam a API do OBS; passam eventos por uma fila
    thread-safe que o timer de ~500 ms (thread do OBS) esvazia.
"""

import os
import queue
import re
import sys
import threading

import obspython as obs

try:
    _HERE = os.path.dirname(os.path.abspath(__file__))
except NameError:  # alguns hospedeiros nao definem __file__
    _HERE = os.path.dirname(os.path.abspath(obs.script_path()))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from bdsm_link import mdns  # noqa: E402
from bdsm_link.manager import (ST_CONNECTED, ST_PENDING, DeviceManager,  # noqa: E402
                               parse_endpoint)
from bdsm_link.storage import SettingsStore  # noqa: E402
from bdsm_link.tally import SceneGraph, TallyTarget, compute_tally  # noqa: E402

TIMER_MS = 500
RECOMPUTE_EVERY_TICKS = 4          # rede de seguranca: recalcula o tally a cada ~2 s
NDI_SOURCE_ID = "ndi_source"       # DistroAV/NDI
NDI_SETTING = "ndi_source_name"

_store = None
_mgr = None
_settings = None
_disc_q = queue.Queue()
_discovering = False
_dirty = True
_ticks = 0
_loaded = False


# ----------------------------------------------------------------------------------
# Log (nunca recebe o token: ele nao sai da camada bdsm_link)
# ----------------------------------------------------------------------------------
def _log(msg, level=None):
    try:
        obs.blog(level if level is not None else obs.LOG_INFO, "[BDSM Link] " + str(msg))
    except Exception:
        pass


# ----------------------------------------------------------------------------------
# Implementacao real do SceneGraph com obspython (referencias liberadas sempre)
# ----------------------------------------------------------------------------------
class ObsSceneGraph(SceneGraph):
    """Cenas/fontes identificadas por NOME; nenhum ponteiro obs_* escapa daqui."""

    @staticmethod
    def _source_name(src):
        return obs.obs_source_get_name(src) if src else None

    def program_scene(self):
        src = obs.obs_frontend_get_current_scene()
        try:
            return self._source_name(src)
        finally:
            if src:
                obs.obs_source_release(src)

    def preview_scene(self):
        if not obs.obs_frontend_preview_program_mode_active():
            return None
        src = obs.obs_frontend_get_current_preview_scene()
        try:
            return self._source_name(src)
        finally:
            if src:
                obs.obs_source_release(src)

    def is_scene(self, source):
        src = obs.obs_get_source_by_name(source)
        if not src:
            return False
        try:
            return obs.obs_scene_from_source(src) is not None
        finally:
            obs.obs_source_release(src)

    def scene_sources(self, scene):
        out = []
        src = obs.obs_get_source_by_name(scene)
        if not src:
            return out
        try:
            sc = obs.obs_scene_from_source(src)  # borrowed: vive enquanto src viver
            if sc:
                self._enum(sc, out, 0)
        finally:
            obs.obs_source_release(src)
        return out

    def _enum(self, scene_obj, out, depth):
        if depth > 8:
            return
        items = obs.obs_scene_enum_items(scene_obj)
        try:
            for item in items or []:
                if not obs.obs_sceneitem_visible(item):
                    continue
                if obs.obs_sceneitem_is_group(item):
                    gscene = obs.obs_sceneitem_group_get_scene(item)
                    if gscene:
                        self._enum(gscene, out, depth + 1)
                    continue
                name = self._source_name(obs.obs_sceneitem_get_source(item))  # borrowed
                if name:
                    out.append(name)
        finally:
            obs.sceneitem_list_release(items)

    def ndi_name_of(self, source):
        src = obs.obs_get_source_by_name(source)
        if not src:
            return None
        try:
            if obs.obs_source_get_unversioned_id(src) != NDI_SOURCE_ID:
                return None
            data = obs.obs_source_get_settings(src)
            try:
                return obs.obs_data_get_string(data, NDI_SETTING) or None
            finally:
                obs.obs_data_release(data)
        finally:
            obs.obs_source_release(src)


def list_ndi_sources():
    """Nomes das fontes NDI do OBS (para o dropdown de mapeamento manual)."""
    names = []
    sources = obs.obs_enum_sources()
    try:
        for s in sources or []:
            if obs.obs_source_get_unversioned_id(s) == NDI_SOURCE_ID:
                names.append(obs.obs_source_get_name(s))
    finally:
        obs.source_list_release(sources)
    return sorted(n for n in names if n)


# ----------------------------------------------------------------------------------
# Tally
# ----------------------------------------------------------------------------------
def _recompute_tally():
    global _dirty
    _dirty = False
    if _mgr is None:
        return
    try:
        targets = [TallyTarget(d.key, d.ndi_stream_name, d.mapping) for d in _mgr.devices.values()]
        _mgr.apply_tally(compute_tally(ObsSceneGraph(), targets))
    except Exception as e:  # nunca derrubar o OBS
        _log("Falha ao calcular o tally: %r" % (e,), obs.LOG_WARNING)


def _on_frontend_event(event):
    global _dirty
    names = ("OBS_FRONTEND_EVENT_SCENE_CHANGED", "OBS_FRONTEND_EVENT_PREVIEW_SCENE_CHANGED",
             "OBS_FRONTEND_EVENT_STUDIO_MODE_ENABLED", "OBS_FRONTEND_EVENT_STUDIO_MODE_DISABLED",
             "OBS_FRONTEND_EVENT_SCENE_COLLECTION_CHANGED", "OBS_FRONTEND_EVENT_FINISHED_LOADING")
    try:
        if event in [getattr(obs, n, object()) for n in names]:
            _dirty = True
            _recompute_tally()  # callback roda na thread do OBS: seguro
    except Exception as e:
        _log("Erro no evento do frontend: %r" % (e,), obs.LOG_WARNING)


# ----------------------------------------------------------------------------------
# Timer (thread do OBS): esvazia a fila de rede, nunca bloqueia
# ----------------------------------------------------------------------------------
def _tick():
    global _ticks
    try:
        if _mgr is None:
            return
        res = _mgr.poll()
        for n in res.notices:
            _log(n, obs.LOG_WARNING if "ateria" in n else obs.LOG_INFO)
        _merge_discovered()
        _ticks += 1
        if res.ndi_changed or _dirty or _ticks % RECOMPUTE_EVERY_TICKS == 0:
            _recompute_tally()
    except Exception as e:
        _log("Erro no timer: %r" % (e,), obs.LOG_WARNING)


# ----------------------------------------------------------------------------------
# Lista de dispositivos nas configuracoes do script
# ----------------------------------------------------------------------------------
def _read_device_list(settings):
    out = []
    arr = obs.obs_data_get_array(settings, "devices")
    if not arr:
        return out
    try:
        for i in range(obs.obs_data_array_count(arr)):
            item = obs.obs_data_array_item(arr, i)
            try:
                out.append(obs.obs_data_get_string(item, "value"))
            finally:
                obs.obs_data_release(item)
    finally:
        obs.obs_data_array_release(arr)
    return out


def _write_device_list(settings, entries):
    arr = obs.obs_data_array_create()
    try:
        for e in entries:
            item = obs.obs_data_create()
            try:
                obs.obs_data_set_string(item, "value", e)
                obs.obs_data_array_push_back(arr, item)
            finally:
                obs.obs_data_release(item)
        obs.obs_data_set_array(settings, "devices", arr)
    finally:
        obs.obs_data_array_release(arr)


def _map_prop(key):
    return "map_" + re.sub(r"[^A-Za-z0-9]", "_", key)


def _discover_async():
    """Busca mDNS em thread propria (bloqueia ~3 s; NAO chama o OBS)."""
    global _discovering
    if _discovering:
        return
    _discovering = True

    def work():
        global _discovering
        try:
            _disc_q.put(mdns.to_endpoints(mdns.discover(3.0)))
        except Exception as e:  # pragma: no cover
            _disc_q.put(e)
        finally:
            _discovering = False

    threading.Thread(target=work, name="bdsm-mdns", daemon=True).start()
    _log("Procurando celulares BDSM na rede (mDNS)...")


def _merge_discovered():
    if _settings is None:
        return
    try:
        res = _disc_q.get_nowait()
    except queue.Empty:
        return
    if isinstance(res, Exception):
        _log("Busca mDNS falhou: %r" % (res,), obs.LOG_WARNING)
        return
    entries = _read_device_list(_settings)
    known = {"%s:%d" % ep for ep in (parse_endpoint(e) for e in entries) if ep}
    added = 0
    for ip, port, label in res:
        key = "%s:%d" % (ip, port)
        if key not in known:
            entries.append(key)
            known.add(key)
            added += 1
            _log("Encontrado: %s (%s)" % (label, key))
    if added:
        _write_device_list(_settings, entries)
        _mgr.sync_devices(entries)
    else:
        _log("Busca mDNS concluida: %d celular(es) ja listado(s)/nenhum novo" % len(res))


# ----------------------------------------------------------------------------------
# Propriedades (UI de Scripts)
# ----------------------------------------------------------------------------------
def _cb_discover(props, prop):
    _discover_async()
    return True


def _cb_refresh(props, prop):
    return True


def _make_pair_cb(key):
    def cb(props, prop):
        if _mgr:
            _mgr.pair(key)
            _log("Pareando com %s: aprove o pedido no celular" % key)
        return True
    return cb


def _make_forget_cb(key):
    def cb(props, prop):
        if _mgr:
            _mgr.forget(key)
            _log("Pareamento de %s esquecido (token apagado)" % key)
        return True
    return cb


def _device_lines(d):
    lines = ["Estado: " + d.status_text()]
    s = d.state
    if s is not None and d.status == ST_CONNECTED:
        bat = "%d%%%s" % (s.battery_level, " (carregando)" if s.is_charging else "")
        if s.battery_level < 20:
            bat += "  <<< BATERIA BAIXA"
        lines.append("Bateria: %s | Lente: %s | Fonte: %s | FPS: %d | REC: %s" % (
            bat, s.camera_lens, s.capture_source, s.fps, "SIM" if s.is_recording else "nao"))
        lines.append("NDI: %s | Microfone: %s" % (s.ndi_source_name or "desligado", s.microphone))
    lines.append("Tally enviado: " + d.tally)
    return "\n".join(lines)


def script_description():
    return ("<b>BDSM Link</b><br>Pareia celulares BDSM, mostra telemetria e envia o "
            "tally (PROGRAM/PREVIEW/OFF) conforme as cenas do OBS. "
            "Adicione o IP do celular (ex.: 192.168.0.20 ou 192.168.0.20:8080), clique em "
            "<i>Parear</i> e aprove no celular conferindo o codigo de 4 digitos.")


def script_defaults(settings):
    obs.obs_data_set_default_bool(settings, "auto_repair", True)


def script_properties():
    props = obs.obs_properties_create()
    status = _mgr.summary() if _mgr else "Script nao carregado"
    obs.obs_properties_add_text(props, "status_info", "Status: " + status, obs.OBS_TEXT_INFO)
    obs.obs_properties_add_editable_list(props, "devices", "Celulares (IP[:porta])",
                                         obs.OBS_EDITABLE_LIST_TYPE_STRINGS, "", "")
    obs.obs_properties_add_button(props, "btn_discover", "Procurar na rede (mDNS)", _cb_discover)
    obs.obs_properties_add_button(props, "btn_refresh", "Atualizar status", _cb_refresh)
    obs.obs_properties_add_bool(props, "auto_repair", "Reparear automaticamente se o celular revogar o acesso (401)")

    if _mgr:
        try:
            ndi_names = list_ndi_sources()
        except Exception as e:
            _log("Nao foi possivel listar fontes NDI: %r" % (e,), obs.LOG_WARNING)
            ndi_names = []
        for key, d in _mgr.devices.items():
            grp = obs.obs_properties_create()
            obs.obs_properties_add_text(grp, "info_" + _map_prop(key), _device_lines(d), obs.OBS_TEXT_INFO)
            obs.obs_properties_add_button(grp, "pair_" + _map_prop(key), "Parear", _make_pair_cb(key))
            obs.obs_properties_add_button(grp, "forget_" + _map_prop(key), "Esquecer", _make_forget_cb(key))
            lst = obs.obs_properties_add_list(grp, _map_prop(key), "Fonte NDI do OBS",
                                              obs.OBS_COMBO_TYPE_LIST, obs.OBS_COMBO_FORMAT_STRING)
            obs.obs_property_list_add_string(lst, "(automatico: BDSM (nome do NDI))", "")
            for n in ndi_names:
                obs.obs_property_list_add_string(lst, n, n)
            obs.obs_properties_add_group(props, "grp_" + _map_prop(key),
                                         "%s  [%s]" % (d.display_name, key), obs.OBS_GROUP_NORMAL, grp)
    return props


def script_update(settings):
    global _settings, _dirty
    _settings = settings
    if _mgr is None:
        return
    try:
        _mgr.auto_repair = obs.obs_data_get_bool(settings, "auto_repair")
        for w in [d.worker for d in _mgr.devices.values() if d.worker]:
            w.auto_repair = _mgr.auto_repair
        invalid = _mgr.sync_devices(_read_device_list(settings))
        for bad in invalid:
            _log("Entrada de celular invalida ignorada: %r" % bad, obs.LOG_WARNING)
        for key in list(_mgr.devices):
            _mgr.set_mapping(key, obs.obs_data_get_string(settings, _map_prop(key)) or "")
        _dirty = True
    except Exception as e:
        _log("Erro em script_update: %r" % (e,), obs.LOG_WARNING)


def script_save(settings):
    # As configuracoes do OBS (lista de celulares e mapeamentos) sao salvas pelo
    # proprio OBS; tokens e clientId ficam em bdsm_link.json (protegidos).
    pass


def script_load(settings):
    global _store, _mgr, _settings, _loaded
    _settings = settings
    try:
        _store = SettingsStore()
        _mgr = DeviceManager(_store, log=_log, auto_repair=obs.obs_data_get_bool(settings, "auto_repair"))
        obs.obs_frontend_add_event_callback(_on_frontend_event)
        obs.timer_add(_tick, TIMER_MS)
        _loaded = True
        _log("Script carregado (clientId estavel; config em %s)" % _store.directory)
    except Exception as e:
        _log("Falha ao carregar o script: %r" % (e,), obs.LOG_ERROR)


def script_unload():
    global _mgr, _loaded
    try:
        obs.timer_remove(_tick)
    except Exception:
        pass
    try:
        obs.obs_frontend_remove_event_callback(_on_frontend_event)
    except Exception:
        pass
    if _mgr is not None:
        try:
            _mgr.shutdown()
        except Exception as e:
            _log("Erro ao encerrar: %r" % (e,), obs.LOG_WARNING)
        _mgr = None
    _loaded = False
