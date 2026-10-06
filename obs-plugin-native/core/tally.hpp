// Motor de tally independente do OBS (porta de bdsm_link/tally.py).
//
// Regra: PROGRAM > PREVIEW > OFF. Transicao em andamento (Fade etc.): ver compute_tally(). Um celular esta "na cena" quando alguma fonte
// VISIVEL da cena (incluindo cenas aninhadas e grupos) e a fonte NDI dele:
//  - mapeamento manual: nome da fonte do OBS == `mapping` do dispositivo; ou
//  - automatico: `ndi_source_name` do OBS casa com "BDSM (<ndiStreamName>)"
//    (sem diferenciar maiusculas, tolerante a espacos, tolerante ao prefixo da maquina).
#pragma once

#include <map>
#include <optional>
#include <string>
#include <vector>

namespace bdsm {

// Abstracao minima do grafo de cenas. Cenas e fontes sao identificadas por NOME:
// assim nenhum ponteiro obs_* vive fora da implementacao real.
class SceneGraph {
public:
	virtual ~SceneGraph() = default;
	virtual std::optional<std::string> program_scene() = 0;
	// Cena em preview; nullopt se fora do Modo Estudio.
	virtual std::optional<std::string> preview_scene() = 0;
	// Fontes VISIVEIS diretamente na cena (grupos ja expandidos), incluindo cenas aninhadas.
	virtual std::vector<std::string> scene_sources(const std::string &scene) = 0;
	virtual bool is_scene(const std::string &source) = 0;
	// `ndi_source_name` se a fonte for do tipo `ndi_source`; senao nullopt.
	virtual std::optional<std::string> ndi_name_of(const std::string &source) = 0;
	// Ha uma transicao de cena em andamento (canal de saida 0 do OBS)? Padrao: nao.
	virtual bool transition_active() { return false; }
	// Cena de ORIGEM (a que esta saindo) da transicao em andamento; nullopt se desconhecida.
	virtual std::optional<std::string> transition_from_scene() { return std::nullopt; }
};

// Espacos colapsados, aparado, sem diferenca de caixa (ASCII + Latin-1/Latin Ext-A basico).
std::string norm(const std::string &s);

// "BDSM (<ndiStreamName>)" ou nullopt se vazio.
std::optional<std::string> expected_ndi_name(const std::optional<std::string> &ndi_stream_name);

struct TallyTarget {
	std::string key;
	std::optional<std::string> ndi_stream_name;
	std::string mapping; // "" = automatico
};

// O nome NDI de uma fonte do OBS (`ndi_source_name`, ex.: "OUTRO-PC (Galaxy A51)") corresponde ao
// aparelho cujo `ndiStreamName` e "Galaxy A51"? Exato "BDSM (nome)" ou so o "(nome)" final
// (prefixo da maquina livre; sem diferenciar maiusculas, tolerante a espacos).
bool ndi_name_matches_stream(const std::string &obs_ndi_name, const std::string &ndi_stream_name);

// Resultado por celular: "PROGRAM" | "PREVIEW" | "OFF".
//
// Transicao em andamento (decisao documentada): quem esta na cena de ORIGEM continua PROGRAM
// (ainda esta no ar ate a transicao terminar); quem so esta na cena de DESTINO fica PREVIEW e vira
// PROGRAM quando a transicao termina (o OBS ja reporta o destino como "cena do programa" no
// inicio da transicao). Sem informacao confiavel da origem (nao resolvivel por nome, p. ex. copia
// privada do Modo Estudio com duplicacao de cenas), usa a regra normal.
std::map<std::string, std::string> compute_tally(SceneGraph &graph, const std::vector<TallyTarget> &targets);

// Alerta de bateria baixa: dispara UMA vez ao cair abaixo de `low` e so rearma
// depois de subir a `rearm` (histerese; evita repeticao com 19/20/19).
class BatteryAlert {
public:
	explicit BatteryAlert(int low = 20, int rearm = 25) : low_(low), rearm_(rearm) {}
	bool update(int level);
	bool alerted() const { return alerted_; }

private:
	int low_, rearm_;
	bool alerted_ = false;
};

} // namespace bdsm
