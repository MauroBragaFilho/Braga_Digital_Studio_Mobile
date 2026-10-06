#include "pairing_proto.hpp"

#include "json.hpp"
#include "util.hpp"

namespace bdsm {

double rate_limit_delay(int attempt)
{
	static const double d[] = {6.0, 12.0, 24.0};
	if (attempt < 0)
		attempt = 0;
	if (attempt > 2)
		attempt = 2;
	return d[attempt];
}

std::string build_pair_request_body(const std::string &client_id, const std::string &client_name)
{
	return "{\"clientId\":" + json_quote(client_id) + ",\"clientName\":" + json_quote(utf8_truncate(client_name, 40)) + "}";
}

static bool fail(PairError &err, PairErrorKind k, int status, const std::string &msg)
{
	err.kind = k;
	err.http_status = status;
	err.message = msg;
	return false;
}

bool parse_pair_request_response(int http_status, const std::string &body, PairRequestInfo &info, PairError &err)
{
	if (http_status == 200) {
		Json j;
		if (!parse_json(body, j) || !j.is_object())
			return fail(err, PairErrorKind::Protocol, http_status, "Resposta de pareamento invalida do celular");
		const Json *id = j.find("requestId");
		const Json *code = j.find("code");
		if (!id || !code || !(id->is_string() || id->is_number()) || !(code->is_string() || code->is_number()))
			return fail(err, PairErrorKind::Protocol, http_status, "Resposta de pareamento invalida do celular");
		info.request_id = id->is_string() ? id->str : std::to_string((long long)id->number);
		info.code = code->is_string() ? code->str : std::to_string((long long)code->number);
		info.expires_in_sec = kPairDefaultTtlS;
		if (const Json *e = j.find("expiresInSec"))
			if (e->is_number() && e->number > 0 && e->number < 3600)
				info.expires_in_sec = (int)e->number;
		return true;
	}
	if (http_status == 400)
		return fail(err, PairErrorKind::BadRequest, 400, "O celular rejeitou o pedido (JSON invalido, HTTP 400)");
	if (http_status == 413)
		return fail(err, PairErrorKind::TooLarge, 413, "Pedido grande demais (HTTP 413)");
	if (http_status == 429)
		return fail(err, PairErrorKind::RateLimited, 429,
			    "O celular esta com um pedido pendente ou recusou ha pouco; aguarde alguns segundos (HTTP 429)");
	return fail(err, PairErrorKind::Protocol, http_status,
		    "Resposta inesperada do celular: HTTP " + std::to_string(http_status));
}

bool parse_pair_status_response(int http_status, const std::string &body, PairOutcome &out, PairError &err)
{
	if (http_status == 404) { // desconhecido: expirou/foi descartado ou veio de outro IP
		out.state = "EXPIRED";
		out.token.reset();
		return true;
	}
	if (http_status != 200)
		return fail(err, PairErrorKind::Protocol, http_status,
			    "Resposta inesperada ao consultar o pareamento: HTTP " + std::to_string(http_status));
	Json j;
	if (!parse_json(body, j) || !j.is_object())
		return fail(err, PairErrorKind::Protocol, http_status, "Resposta de status invalida do celular");
	const Json *st = j.find("state");
	if (!st || !st->is_string())
		return fail(err, PairErrorKind::Protocol, http_status, "Resposta de status invalida do celular");
	std::string s = st->str;
	for (auto &c : s)
		if (c >= 'a' && c <= 'z')
			c = (char)(c - 'a' + 'A');
	out.state = s;
	out.token.reset();
	if (const Json *t = j.find("token"))
		if (t->is_string() && !t->str.empty())
			out.token = t->str;
	return true;
}

std::string url_encode(const std::string &s)
{
	static const char *hex = "0123456789ABCDEF";
	std::string o;
	for (unsigned char c : s) {
		if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' ||
		    c == '.' || c == '~') {
			o += (char)c;
		} else {
			o += '%';
			o += hex[c >> 4];
			o += hex[c & 15];
		}
	}
	return o;
}

} // namespace bdsm
