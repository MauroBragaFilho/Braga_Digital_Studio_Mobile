"""Modelo do ``LinkState`` publicado pelo celular a ~2 Hz (WS /ws/link)."""

import json
from dataclasses import dataclass
from typing import Any, Optional

TALLY_STATES = ("OFF", "PREVIEW", "PROGRAM")


def _as_int(v: Any, default: int = 0) -> int:
    try:
        if isinstance(v, bool):
            return default
        return int(v)
    except (TypeError, ValueError):
        return default


def _as_bool(v: Any, default: bool = False) -> bool:
    return v if isinstance(v, bool) else default


def _as_str(v: Any, default: str = "--") -> str:
    return v if isinstance(v, str) else default


@dataclass
class LinkState:
    """Telemetria do celular. Parse tolerante: campos ausentes recebem padrao e
    campos desconhecidos sao ignorados (o servidor pode evoluir)."""

    device_name: str = "BDSM Device"
    battery_level: int = 0
    is_charging: bool = False
    capture_source: str = "--"
    camera_lens: str = "--"
    fps: int = 0
    microphone: str = "--"
    is_recording: bool = False
    ndi_stream_name: Optional[str] = None  # None = NDI desligado
    tally: str = "OFF"

    @classmethod
    def from_json(cls, text: str) -> "LinkState":
        obj = json.loads(text)
        if not isinstance(obj, dict):
            raise ValueError("LinkState nao e um objeto JSON")
        return cls.from_dict(obj)

    @classmethod
    def from_dict(cls, d: dict) -> "LinkState":
        ndi = d.get("ndiStreamName")
        tally = _as_str(d.get("tally"), "OFF").upper()
        if tally not in TALLY_STATES:
            tally = "OFF"
        return cls(
            device_name=_as_str(d.get("deviceName"), "BDSM Device"),
            battery_level=max(0, min(100, _as_int(d.get("batteryLevel")))),
            is_charging=_as_bool(d.get("isCharging")),
            capture_source=_as_str(d.get("captureSource")),
            camera_lens=_as_str(d.get("cameraLens")),
            fps=_as_int(d.get("fps")),
            microphone=_as_str(d.get("microphone")),
            is_recording=_as_bool(d.get("isRecording")),
            ndi_stream_name=ndi if isinstance(ndi, str) and ndi.strip() else None,
            tally=tally,
        )

    @property
    def ndi_source_name(self) -> Optional[str]:
        """Nome da fonte como o OBS/NDI a mostra: ``BDSM (<ndiStreamName>)``."""
        if self.ndi_stream_name is None:
            return None
        return "BDSM (%s)" % self.ndi_stream_name
