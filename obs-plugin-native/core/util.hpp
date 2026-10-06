// Utilitarios puros: backoff de reconexao, enderecos de celular, nome do cliente.
#pragma once

#include <algorithm>
#include <cmath>
#include <string>

namespace bdsm {

constexpr int kDefaultPort = 8080;

// Backoff exponencial 1, 2, 4, ... ate `cap` (30 s). Igual ao Backoff do Python.
class Backoff {
public:
	explicit Backoff(double base = 1.0, double cap = 30.0, double factor = 2.0)
		: base_(base), cap_(cap), factor_(factor)
	{
	}
	double next()
	{
		double d = std::min(cap_, base_ * std::pow(factor_, (double)n_));
		if (n_ < 64)
			++n_;
		return d;
	}
	void reset() { n_ = 0; }

private:
	double base_, cap_, factor_;
	int n_ = 0;
};

struct Endpoint {
	std::string host;
	int port = kDefaultPort;
	// "host:porta" (a chave estavel do dispositivo)
	std::string key() const { return host + ":" + std::to_string(port); }
};

// '192.168.0.5', '192.168.0.5:8080', 'http://host:8080/' -> Endpoint; false se invalido.
bool parse_endpoint(const std::string &text, Endpoint &out);

// "OBS Studio (NOME-DO-PC)" limitado a 40 caracteres (limite do servidor), sem
// cortar no meio de um caractere UTF-8.
std::string make_client_name(const std::string &pc_name);

// Trunca `s` em no maximo `max_chars` pontos de codigo UTF-8.
std::string utf8_truncate(const std::string &s, size_t max_chars);

} // namespace bdsm
