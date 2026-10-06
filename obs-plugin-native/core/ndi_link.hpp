// Integracao com o DistroAV (plugin NDI do OBS): logica PURA (sem OBS, sem Qt).
//
// O DistroAV registra a fonte de entrada `ndi_source` (OBS_SOURCE_TYPE_INPUT) e guarda o nome
// da fonte NDI remota na configuracao `ndi_source_name` (string, formato "MAQUINA (nome)").
// Verificado no codigo do DistroAV 6.2.1 (src/ndi-source.cpp): id "ndi_source", chave
// "ndi_source_name". O nosso celular anuncia "BDSM (<ndiStreamName>)".
//
// Aqui fica so a DECISAO (reutilizar, adicionar existente ou criar) e os nomes; a parte que
// fala com o OBS esta em src/obs_ndi_integration.*.
#pragma once

#include <optional>
#include <set>
#include <string>
#include <vector>

namespace bdsm {

// Uma fonte `ndi_source` ja existente no OBS.
struct ObsNdiSourceInfo {
	std::string name;                // nome da fonte no OBS
	std::string ndi_name;            // `ndi_source_name` configurado
	std::vector<std::string> scenes; // cenas que contem a fonte (qualquer nivel de grupo)
};

enum class AddAction {
	NoNdiName,    // NDI desligado no celular (sem ndiStreamName): nao ha o que adicionar
	AlreadyInScene, // ja existe E ja esta na cena alvo: nada a fazer
	AddExisting,  // existe em outra(s) cena(s): reutiliza a mesma fonte na cena alvo
	CreateNew,    // nao existe: cria a fonte e adiciona na cena alvo
};

struct AddPlan {
	AddAction action = AddAction::NoNdiName;
	std::string source_name;            // fonte existente a reutilizar OU nome da fonte a criar
	std::string ndi_name;               // "BDSM (<ndiStreamName>)" (valor de ndi_source_name ao criar)
	std::vector<std::string> in_scenes; // (existente) cenas onde ja esta
};

// "BDSM - <aparelho>" (aparado, sem caracteres de controle, <= 64 caracteres); "BDSM - Celular" se vazio.
std::string friendly_source_name(const std::string &device_name);

// `base` se livre; senao "base (2)", "base (3)"... (comparacao exata, como o OBS).
std::string unique_source_name(const std::string &base, const std::set<std::string> &taken);

// Decide o que fazer ao clicar "Adicionar fonte NDI a cena atual".
//  - `ndi_sources`: todas as fontes ndi_source do OBS;
//  - `taken_names`: nomes de TODAS as fontes e cenas do OBS (para nao repetir o nome ao criar);
//  - `target_scene`: cena onde a fonte entrara (cena atual; no Modo Estudio, a de preview).
// Preferencia entre varias fontes casando: exata "BDSM (nome)" > ja na cena alvo > ja em alguma
// cena > ordem alfabetica do nome (determinista).
AddPlan plan_add_ndi_source(const std::string &device_name, const std::optional<std::string> &ndi_stream_name,
			    const std::string &target_scene, const std::vector<ObsNdiSourceInfo> &ndi_sources,
			    const std::set<std::string> &taken_names);

} // namespace bdsm
