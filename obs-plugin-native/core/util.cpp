#include "util.hpp"

#include <cctype>

namespace bdsm {

namespace {

bool starts_with_ci(const std::string &s, const char *prefix)
{
	size_t n = 0;
	while (prefix[n])
		++n;
	if (s.size() < n)
		return false;
	for (size_t i = 0; i < n; ++i)
		if (std::tolower((unsigned char)s[i]) != std::tolower((unsigned char)prefix[i]))
			return false;
	return true;
}

std::string trim(const std::string &s)
{
	size_t b = s.find_first_not_of(" \t\r\n\f\v");
	if (b == std::string::npos)
		return "";
	size_t e = s.find_last_not_of(" \t\r\n\f\v");
	return s.substr(b, e - b + 1);
}

} // namespace

bool parse_endpoint(const std::string &text, Endpoint &out)
{
	std::string s = trim(text);
	for (const char *p : {"http://", "https://", "ws://", "wss://"}) {
		if (starts_with_ci(s, p)) {
			s = s.substr(std::string(p).size());
			break; // o Python remove cada prefixo em sequencia; um e suficiente na pratica
		}
	}
	size_t slash = s.find('/');
	if (slash != std::string::npos)
		s = s.substr(0, slash);
	s = trim(s);
	if (s.empty())
		return false;
	std::string host = s;
	int port = kDefaultPort;
	size_t colon = s.rfind(':');
	if (colon != std::string::npos) {
		host = s.substr(0, colon);
		std::string p = s.substr(colon + 1);
		if (p.empty() || p.size() > 5)
			return false;
		for (char c : p)
			if (c < '0' || c > '9')
				return false;
		int v = std::stoi(p);
		if (v <= 0 || v >= 65536)
			return false;
		port = v;
	}
	if (host.empty())
		return false;
	for (char c : host)
		if (std::isspace((unsigned char)c))
			return false;
	out.host = host;
	out.port = port;
	return true;
}

std::string utf8_truncate(const std::string &s, size_t max_chars)
{
	size_t count = 0, i = 0;
	while (i < s.size()) {
		if (count == max_chars)
			return s.substr(0, i);
		unsigned char c = (unsigned char)s[i];
		size_t n = c < 0x80 ? 1 : ((c & 0xE0) == 0xC0 ? 2 : ((c & 0xF0) == 0xE0 ? 3 : ((c & 0xF8) == 0xF0 ? 4 : 1)));
		i += n;
		++count;
	}
	return s;
}

std::string make_client_name(const std::string &pc_name)
{
	const std::string prefix = "OBS Studio (";
	std::string pc = pc_name.empty() ? "PC" : pc_name;
	// 40 - len(prefix) - 1 caracteres para o nome do PC + ")" = 40 no maximo
	return prefix + utf8_truncate(pc, 40 - prefix.size() - 1) + ")";
}

} // namespace bdsm
