// Integracao com o DistroAV (plugin NDI do OBS) sobre libobs + obs-frontend-api.
// Chamar SEMPRE da thread da UI (Qt principal). Nenhum ponteiro obs_* escapa daqui e toda
// referencia obtida (get_by_name / frontend / transition) e liberada com obs_source_release.
//
// DistroAV 6.2.1 (src/ndi-source.cpp): id da fonte "ndi_source", chave "ndi_source_name",
// nome NDI "MAQUINA (nome)". A decisao (reutilizar/criar) e pura: core/ndi_link.
#pragma once

#include <optional>
#include <set>
#include <string>
#include <vector>

#include "ndi_link.hpp"

namespace bdsm_qt {

struct ObsNdiScan {
	bool distroav = false;                       // o tipo de fonte `ndi_source` esta registrado no OBS
	std::string target_scene;                    // cena atual (no Modo Estudio: a de preview)
	std::vector<bdsm::ObsNdiSourceInfo> sources; // fontes ndi_source existentes
	std::set<std::string> taken_names;           // nomes de todas as fontes e cenas
};

// O DistroAV esta instalado E carregado? (o DistroAV nao registra `ndi_source` se o NDI Runtime faltar).
bool distroav_available();

// Fotografia do estado atual do OBS (barata: dezenas de fontes/cenas).
ObsNdiScan scan_ndi();

struct AddOutcome {
	bool ok = false;
	bdsm::AddAction action = bdsm::AddAction::NoNdiName;
	std::string source_name;
	std::string target_scene;
	std::string error_key; // chave de locale quando !ok: BdsmLink.Err.*
};

// Executa o plano de bdsm::plan_add_ndi_source na cena alvo e devolve o que foi feito.
AddOutcome add_ndi_source_for(const std::string &device_name, const std::optional<std::string> &ndi_stream_name);

} // namespace bdsm_qt
