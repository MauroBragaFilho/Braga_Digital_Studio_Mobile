// Modelo do LinkState publicado pelo celular a ~2 Hz (WS /ws/link).
// Parse tolerante, igual ao bdsm_link/state.py: campos ausentes recebem padrao,
// campos desconhecidos sao ignorados.
#pragma once

#include <optional>
#include <string>

namespace bdsm {

struct LinkState {
	std::string device_name = "BDSM Device";
	int battery_level = 0;
	bool is_charging = false;
	std::string capture_source = "--";
	std::string camera_lens = "--";
	int fps = 0;
	std::string microphone = "--";
	bool is_recording = false;
	std::optional<std::string> ndi_stream_name; // sem valor = NDI desligado
	std::string tally = "OFF";                  // OFF | PREVIEW | PROGRAM

	// "BDSM (<ndiStreamName>)" ou nullopt com o NDI desligado.
	std::optional<std::string> ndi_source_name() const;

	// Primeiro quadro "vazio" (o coletor do app ainda nao preencheu a telemetria):
	// fonte "--" e bateria 0. O gerenciador ignora esses quadros (como o script Python).
	bool is_placeholder() const { return capture_source == "--" && battery_level == 0; }

	// Falha (false) se o texto nao for JSON ou nao for um objeto.
	static bool from_json(const std::string &text, LinkState &out, std::string *error = nullptr);
};

// Mensagem do cliente para o celular: {"type":"TALLY_UPDATE","state":"PROGRAM"}.
// `state` invalido vira "OFF". O servidor ignora mensagens > 1024 caracteres ou de outro tipo.
std::string tally_update_message(const std::string &state);

} // namespace bdsm
