"""Persistencia do clientId e dos tokens (``bdsm_link.json``).

O token e protegido: no Windows com DPAPI (CryptProtectData, escopo do usuario);
nos outros SO o arquivo recebe chmod 600. O token NUNCA e logado.
"""

import base64
import json
import os
import sys
import threading
import uuid
from typing import Dict, Optional

FILE_NAME = "bdsm_link.json"


def default_config_dir() -> str:
    """Pasta de configuracao do script (``BDSM_LINK_CONFIG_DIR`` sobrescreve)."""
    env = os.environ.get("BDSM_LINK_CONFIG_DIR")
    if env:
        return env
    if sys.platform.startswith("win"):
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        return os.path.join(base, "obs-studio", "plugin_config", "bdsm_link")
    if sys.platform == "darwin":
        return os.path.expanduser("~/Library/Application Support/obs-studio/plugin_config/bdsm_link")
    return os.path.expanduser("~/.config/obs-studio/plugin_config/bdsm_link")


# --- DPAPI (Windows) ----------------------------------------------------------------

def _dpapi(data: bytes, protect: bool) -> bytes:
    import ctypes
    from ctypes import wintypes

    class DATA_BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    kernel32.LocalFree.restype = ctypes.c_void_p
    fn = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
    fn.restype = wintypes.BOOL
    buf = ctypes.create_string_buffer(data, len(data))
    blob_in = DATA_BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    blob_out = DATA_BLOB()
    if protect:
        ok = fn(ctypes.byref(blob_in), "BDSM Link", None, None, None, 0, ctypes.byref(blob_out))
    else:
        ok = fn(ctypes.byref(blob_in), None, None, None, None, 0, ctypes.byref(blob_out))
    if not ok:
        raise OSError("DPAPI falhou (erro %d)" % ctypes.GetLastError())
    try:
        return ctypes.string_at(blob_out.pbData, blob_out.cbData)
    finally:
        kernel32.LocalFree(ctypes.cast(blob_out.pbData, ctypes.c_void_p))


def protect_secret(secret: str, use_dpapi: Optional[bool] = None) -> str:
    """Devolve ``dpapi:<b64>`` (Windows) ou ``plain:<b64>`` (arquivo 600 em outros SO)."""
    raw = secret.encode("utf-8")
    if use_dpapi is None:
        use_dpapi = sys.platform.startswith("win")
    if use_dpapi:
        return "dpapi:" + base64.b64encode(_dpapi(raw, True)).decode("ascii")
    return "plain:" + base64.b64encode(raw).decode("ascii")


def unprotect_secret(blob: str) -> Optional[str]:
    """Inverso de protect_secret. Devolve None se ilegivel (outro usuario/PC)."""
    try:
        scheme, _, b64 = blob.partition(":")
        raw = base64.b64decode(b64)
        if scheme == "dpapi":
            return _dpapi(raw, False).decode("utf-8")
        if scheme == "plain":
            return raw.decode("utf-8")
    except Exception:
        pass
    return None


class SettingsStore:
    """``bdsm_link.json``: {clientId, devices: {"ip:porta": {token, name}}}. Thread-safe."""

    def __init__(self, directory: Optional[str] = None, use_dpapi: Optional[bool] = None):
        self.directory = directory or default_config_dir()
        self.path = os.path.join(self.directory, FILE_NAME)
        self._use_dpapi = use_dpapi
        self._lock = threading.RLock()
        self._data: dict = {"clientId": "", "devices": {}}
        self._load()
        if not self._data.get("clientId"):
            self._data["clientId"] = "obs-" + str(uuid.uuid4())
            self._save()

    # -- arquivo --
    def _load(self) -> None:
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                d = json.load(f)
            if isinstance(d, dict):
                d.setdefault("devices", {})
                if not isinstance(d["devices"], dict):
                    d["devices"] = {}
                self._data = d
        except (OSError, ValueError):
            pass

    def _save(self) -> None:
        os.makedirs(self.directory, exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self._data, f, indent=2)
        try:
            os.chmod(tmp, 0o600)  # no Windows e quase inocuo; a protecao e o DPAPI
        except OSError:
            pass
        os.replace(tmp, self.path)

    # -- API --
    @property
    def client_id(self) -> str:
        return self._data["clientId"]

    def get_token(self, key: str) -> Optional[str]:
        with self._lock:
            blob = self._data["devices"].get(key, {}).get("token")
            return unprotect_secret(blob) if blob else None

    def set_token(self, key: str, token: str, name: str = "") -> None:
        with self._lock:
            dev = self._data["devices"].setdefault(key, {})
            dev["token"] = protect_secret(token, self._use_dpapi)
            if name:
                dev["name"] = name
            self._save()

    def clear_token(self, key: str) -> None:
        with self._lock:
            dev = self._data["devices"].get(key)
            if dev and "token" in dev:
                del dev["token"]
                self._save()

    def get_meta(self, key: str, field: str, default=None):
        with self._lock:
            return self._data["devices"].get(key, {}).get(field, default)

    def set_meta(self, key: str, field: str, value) -> None:
        with self._lock:
            self._data["devices"].setdefault(key, {})[field] = value
            self._save()

    def known_keys(self):
        with self._lock:
            return list(self._data["devices"].keys())
