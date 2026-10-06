"""Motor de tally: independente do OBS (usa a abstracao ``SceneGraph``).

Regra: PROGRAM > PREVIEW > OFF. Um celular esta "na cena" quando alguma fonte
VISIVEL da cena (incluindo cenas aninhadas e grupos) e a fonte NDI dele:
 - mapeamento manual: nome da fonte do OBS == ``mapping`` do dispositivo; ou
 - automatico: ``ndi_source_name`` do OBS casa com ``BDSM (<ndiStreamName>)``
   (sem diferenciar maiusculas, tolerante a espacos).
"""

import re
from typing import Callable, Dict, Iterable, List, Optional, Set


class SceneGraph:
    """Abstracao minima do grafo de cenas. Cenas e fontes sao identificadas por
    NOME (str): assim nao ha ponteiros obs_* vivos fora da implementacao real."""

    def program_scene(self) -> Optional[str]:
        raise NotImplementedError

    def preview_scene(self) -> Optional[str]:
        """Cena em preview; None se fora do Modo Estudio."""
        raise NotImplementedError

    def scene_sources(self, scene: str) -> List[str]:
        """Fontes VISIVEIS diretamente na cena (grupos ja expandidos), incluindo
        cenas aninhadas (como nomes de fonte)."""
        raise NotImplementedError

    def is_scene(self, source: str) -> bool:
        raise NotImplementedError

    def ndi_name_of(self, source: str) -> Optional[str]:
        """``ndi_source_name`` se a fonte for do tipo ``ndi_source``; senao None."""
        raise NotImplementedError


def norm(s: Optional[str]) -> str:
    """Normaliza para comparacao: espacos colapsados, aparado, sem caixa."""
    return re.sub(r"\s+", " ", (s or "")).strip().casefold()


def expected_ndi_name(ndi_stream_name: Optional[str]) -> Optional[str]:
    return None if not ndi_stream_name else "BDSM (%s)" % ndi_stream_name


def collect(graph: SceneGraph, scene: Optional[str]):
    """Devolve (nomes NDI normalizados, nomes de fonte normalizados) alcancaveis."""
    ndi: Set[str] = set()
    names: Set[str] = set()
    if scene is None:
        return ndi, names
    visited: Set[str] = set()

    def walk(sc: str, depth: int) -> None:
        if sc in visited or depth > 16:  # protege contra ciclos
            return
        visited.add(sc)
        for src in graph.scene_sources(sc):
            if graph.is_scene(src):
                names.add(norm(src))
                walk(src, depth + 1)
                continue
            names.add(norm(src))
            n = graph.ndi_name_of(src)
            if n:
                ndi.add(norm(n))

    walk(scene, 0)
    return ndi, names


class TallyTarget:
    """Dados de um celular relevantes para o tally."""

    def __init__(self, key: str, ndi_stream_name: Optional[str] = None, mapping: str = ""):
        self.key, self.ndi_stream_name, self.mapping = key, ndi_stream_name, mapping


def _matches(target: TallyTarget, ndi: Set[str], names: Set[str]) -> bool:
    if target.mapping:
        return norm(target.mapping) in names
    exp = expected_ndi_name(target.ndi_stream_name)
    if exp is None:
        return False
    e = norm(exp)
    if e in ndi:
        return True
    # tolerancia: a parte "MAQUINA" pode diferir; compara o "(sender)" final
    suffix = "(" + norm(target.ndi_stream_name) + ")"
    return any(n.endswith(suffix) for n in ndi)


def compute_tally(graph: SceneGraph, targets: Iterable[TallyTarget]) -> Dict[str, str]:
    prog_ndi, prog_names = collect(graph, graph.program_scene())
    prev_ndi, prev_names = collect(graph, graph.preview_scene())
    out: Dict[str, str] = {}
    for t in targets:
        if _matches(t, prog_ndi, prog_names):
            out[t.key] = "PROGRAM"
        elif _matches(t, prev_ndi, prev_names):
            out[t.key] = "PREVIEW"
        else:
            out[t.key] = "OFF"
    return out


class BatteryAlert:
    """Alerta de bateria baixa: dispara UMA vez ao cair abaixo de ``low`` e so
    rearma depois que sobe a ``rearm`` (histerese, evita repeticao com 19/20/19)."""

    def __init__(self, low: int = 20, rearm: int = 25):
        self.low, self.rearm = low, rearm
        self.alerted = False

    def update(self, level: int) -> bool:
        if not self.alerted and level < self.low:
            self.alerted = True
            return True
        if self.alerted and level >= self.rearm:
            self.alerted = False
        return False
