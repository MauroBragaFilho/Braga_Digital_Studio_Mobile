// mDNS minimo (porta de bdsm_link/mdns.py): monta a consulta PTR
// `_bdsm._tcp.local` e interpreta respostas PTR/SRV/A/TXT. O envio/recepcao UDP
// fica na camada Qt (QUdpSocket). O app registra `_bdsm._tcp` via Android NSD
// (nome "BDSM Link", sem TXT).
#pragma once

#include <map>
#include <string>
#include <utility>
#include <vector>

namespace bdsm {

constexpr const char *kMdnsGroup = "224.0.0.251";
constexpr int kMdnsPort = 5353;
constexpr const char *kMdnsService = "_bdsm._tcp.local";
constexpr int T_A = 1, T_PTR = 12, T_TXT = 16, T_AAAA = 28, T_SRV = 33;

struct ServiceInstance {
	std::string name; // ex.: "BDSM Link._bdsm._tcp.local"
	std::string host; // alvo do SRV
	int port = 0;
	std::vector<std::string> addresses;
	std::map<std::string, std::string> txt;

	std::string label() const; // "BDSM Link"
};

std::string mdns_encode_name(const std::string &name);
std::string mdns_build_query(const std::string &name = kMdnsService, int qtype = T_PTR, bool unicast = false);

// Tolerante: pacote malformado devolve o que foi lido ate o erro. `sender` (IPv4 em
// texto) e usado se nao houver registro A.
std::vector<ServiceInstance> mdns_parse_response(const std::string &data, const std::string &sender = "",
						 const std::string &service = kMdnsService);

// (ip, porta, nome) prontos para a lista de dispositivos.
struct MdnsEndpoint {
	std::string ip;
	int port;
	std::string label;
};
std::vector<MdnsEndpoint> mdns_to_endpoints(const std::vector<ServiceInstance> &instances);

} // namespace bdsm
