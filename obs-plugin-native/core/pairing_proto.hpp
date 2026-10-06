// Protocolo de pareamento (consentimento duplo) SEM rede: monta os corpos e
// interpreta as respostas HTTP. Porta de bdsm_link/pairing.py. A camada Qt faz
// o POST /api/pair/request e o GET /api/pair/status/{id}.
//
// Limites do servidor (LinkModule.kt / LinkAuthManager.kt):
//  - corpo do POST <= 2 KB; clientName <= 40 caracteres (sanitizado no servidor)
//  - 400 JSON invalido, 413 corpo grande, 429 pedido pendente do mesmo IP /
//    3 pendentes / cooldown de 5 s apos recusa
//  - pedido expira em 90 s; codigo de 4 digitos; token entregue UMA unica vez
//  - status de outro IP: 404
#pragma once

#include <optional>
#include <string>

namespace bdsm {

constexpr double kPairPollIntervalS = 1.5;
constexpr double kPairExtraWaitS = 5.0;
constexpr int kPairDefaultTtlS = 90;
constexpr size_t kPairMaxBodyBytes = 2048;
constexpr int kMaxRateLimitRetries = 3;

// Espera (em s) antes da n-esima nova tentativa apos 429: 6, 12, 24.
double rate_limit_delay(int attempt);

enum class PairErrorKind { None, Network, BadRequest, TooLarge, RateLimited, Protocol, Cancelled };

struct PairError {
	PairErrorKind kind = PairErrorKind::None;
	int http_status = 0;
	std::string message; // pt-BR, pronta para o usuario
};

struct PairRequestInfo {
	std::string request_id;
	std::string code; // 4 digitos
	int expires_in_sec = kPairDefaultTtlS;
};

struct PairOutcome {
	std::string state; // PENDING | APPROVED | DENIED | EXPIRED
	std::optional<std::string> token;
};

// {"clientId":"...","clientName":"..."} (clientName limitado a 40 caracteres)
std::string build_pair_request_body(const std::string &client_id, const std::string &client_name);

// POST /api/pair/request: status 200 -> true e `info`; senao false e `err`.
bool parse_pair_request_response(int http_status, const std::string &body, PairRequestInfo &info, PairError &err);

// GET /api/pair/status/{id}: 404 vira EXPIRED. Falha de protocolo -> false e `err`.
bool parse_pair_status_response(int http_status, const std::string &body, PairOutcome &out, PairError &err);

// Codificacao percentual (RFC 3986) para montar "?token=...".
std::string url_encode(const std::string &s);

} // namespace bdsm
