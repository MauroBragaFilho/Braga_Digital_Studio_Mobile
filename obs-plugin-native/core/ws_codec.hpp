// Protocolo WebSocket (RFC 6455) puro, SEM socket: base64, SHA-1, handshake,
// codificacao/decodificacao de quadros e montagem de mensagens. Porta de
// bdsm_link/websocket.py. O transporte (QTcpSocket) fica na camada Qt.
//
// Motivo: o Qt6 pre-compilado do OBS (obs-deps) NAO traz o modulo Qt WebSockets.
#pragma once

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace bdsm {

constexpr int OP_CONT = 0x0, OP_TEXT = 0x1, OP_BINARY = 0x2, OP_CLOSE = 0x8, OP_PING = 0x9, OP_PONG = 0xA;
constexpr size_t kMaxSendFrame = 8 * 1024;      // limite do servidor (maxFrameSize)
constexpr size_t kMaxRecvMessage = 256 * 1024;  // defesa contra servidor malicioso
constexpr size_t kMaxHandshakeBytes = 16384;

struct WsError : std::runtime_error {
	using std::runtime_error::runtime_error;
};

// ---- primitivas ----
std::string base64_encode(const std::string &raw);
bool base64_decode(const std::string &text, std::string &raw);
std::string sha1_raw(const std::string &data); // 20 bytes
std::string ws_accept_key(const std::string &client_key); // base64(sha1(key + GUID))
std::string ws_generate_key();                            // base64 de 16 bytes aleatorios

// ---- handshake ----
// path_and_query ja deve estar codificado (ex.: "/ws/link?token=abc").
std::string build_handshake_request(const std::string &host, int port, const std::string &path_and_query,
				    const std::string &client_key);

enum class HandshakeState { NeedMore, Ok, Failed };

struct HandshakeResult {
	HandshakeState state = HandshakeState::NeedMore;
	int http_status = 0;    // ex.: 401 (so quando Failed por HTTP)
	std::string error;      // mensagem
	size_t consumed = 0;    // bytes do buffer ate o fim dos cabecalhos
};

// Interpreta o inicio de `buf` (resposta do servidor). Ok = 101 + Sec-WebSocket-Accept correto.
HandshakeResult parse_handshake_response(const std::string &buf, const std::string &client_key);

// ---- quadros ----
// `mask_key` (4 bytes) opcional, para testes; vazio = aleatoria. Quadros de cliente DEVEM ser mascarados.
std::string encode_frame(int opcode, const std::string &payload = std::string(), bool mask = true, bool fin = true,
			 const std::string &mask_key = std::string());

struct Frame {
	bool fin = true;
	int opcode = 0;
	std::string payload;
};

// Decodificador incremental: alimente com bytes, receba quadros completos.
class FrameDecoder {
public:
	explicit FrameDecoder(size_t max_payload = kMaxRecvMessage) : max_(max_payload) {}
	// Acrescenta bytes e devolve os quadros completos. Levanta WsError em violacao de protocolo.
	std::vector<Frame> feed(const char *data, size_t n);
	std::vector<Frame> feed(const std::string &s) { return feed(s.data(), s.size()); }

private:
	bool try_one(Frame &f);
	std::string buf_;
	size_t max_;
};

// Camada de mensagens: junta fragmentos, responde ping e trata close.
class WsReceiver {
public:
	// Processa bytes recebidos. Levanta WsError em protocolo invalido.
	void feed(const char *data, size_t n);
	void feed(const std::string &s) { feed(s.data(), s.size()); }

	// Mensagens de texto completas (esvazie apos ler).
	std::vector<std::string> messages;
	// Bytes que a camada de transporte deve ENVIAR (pong, eco de close) (esvazie apos enviar).
	std::string outgoing;

	bool closed = false;
	bool has_close_code = false;
	int close_code = 0;
	std::string close_reason;
	bool sent_close = false; // marque se voce ja enviou um quadro close

private:
	void handle(const Frame &f);
	FrameDecoder dec_;
	int frag_op_ = -1;
	std::string frag_;
};

// "Texto -> quadro de texto mascarado"; levanta std::length_error acima de kMaxSendFrame.
std::string encode_text_message(const std::string &text);
std::string encode_close(int code, const std::string &reason = std::string());

} // namespace bdsm
