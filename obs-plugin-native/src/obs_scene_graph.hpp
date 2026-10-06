// Implementacao real do SceneGraph (core/tally.hpp) sobre libobs +
// obs-frontend-api. Cenas e fontes sao identificadas por NOME; nenhum ponteiro
// obs_* escapa deste modulo e todas as referencias sao liberadas.
//
// Chamar SEMPRE da thread da UI (Qt principal) do OBS.
#pragma once

#include <string>
#include <vector>

#include "tally.hpp"

namespace bdsm_qt {

constexpr const char *kNdiSourceId = "ndi_source";        // DistroAV (ex-obs-ndi)
constexpr const char *kNdiSettingName = "ndi_source_name"; // configuracao da fonte NDI

class ObsSceneGraph : public bdsm::SceneGraph {
public:
	std::optional<std::string> program_scene() override;
	std::optional<std::string> preview_scene() override;
	std::vector<std::string> scene_sources(const std::string &scene) override;
	bool is_scene(const std::string &source) override;
	std::optional<std::string> ndi_name_of(const std::string &source) override;
	// Transicao em andamento no canal de saida 0 (a "fonte" de transicao do OBS) e a cena de ORIGEM (lado A).
	bool transition_active() override;
	std::optional<std::string> transition_from_scene() override;
};

// Nomes das fontes NDI (tipo `ndi_source`) do OBS, ordenados; para o seletor de mapeamento.
std::vector<std::string> list_ndi_sources();

} // namespace bdsm_qt
