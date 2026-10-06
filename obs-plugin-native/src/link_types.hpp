// Tipos compartilhados entre a conexao, o gerenciador e o dock (camada Qt).
#pragma once

namespace bdsm_qt {

// Estados de conexao de UM celular (equivalentes aos ST_* do manager.py).
enum class ConnStatus {
	Disconnected, // desconectado, tentando reconectar
	Unpaired,     // sem token
	Pending,      // aguardando aprovacao no celular
	Connecting,
	Connected,
	Error,
};

} // namespace bdsm_qt
