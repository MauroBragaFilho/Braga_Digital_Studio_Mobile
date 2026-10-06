#include "mdns.hpp"

#include <algorithm>
#include <cctype>
#include <stdexcept>

namespace bdsm {

namespace {

struct ParseError : std::runtime_error {
	using std::runtime_error::runtime_error;
};

std::string lower(std::string s)
{
	for (auto &c : s)
		c = (char)std::tolower((unsigned char)c);
	return s;
}

unsigned u8(const std::string &d, size_t i)
{
	if (i >= d.size())
		throw ParseError("truncado");
	return (unsigned char)d[i];
}
unsigned u16(const std::string &d, size_t i) { return (u8(d, i) << 8) | u8(d, i + 1); }

// Le um nome (com compressao). Devolve o nome e a posicao apos ele no fluxo.
std::string read_name(const std::string &data, size_t pos, size_t &next)
{
	std::string out;
	bool first = true;
	long end = -1;
	int jumps = 0;
	while (true) {
		if (pos >= data.size())
			throw ParseError("nome truncado");
		unsigned n = (unsigned char)data[pos];
		if (n == 0) {
			pos += 1;
			break;
		}
		if ((n & 0xC0) == 0xC0) {
			if (pos + 1 >= data.size())
				throw ParseError("ponteiro truncado");
			size_t ptr = ((n & 0x3F) << 8) | (unsigned char)data[pos + 1];
			if (end < 0)
				end = (long)pos + 2;
			pos = ptr;
			if (++jumps > 20)
				throw ParseError("laco de compressao");
			continue;
		}
		if (n & 0xC0)
			throw ParseError("rotulo invalido");
		if (!first)
			out += '.';
		first = false;
		out += data.substr(pos + 1, n);
		pos += 1 + n;
	}
	next = end >= 0 ? (size_t)end : pos;
	return out;
}

bool ends_with(const std::string &s, const std::string &suf)
{
	return s.size() >= suf.size() && s.compare(s.size() - suf.size(), suf.size(), suf) == 0;
}

std::string ipv4_text(const std::string &rd)
{
	return std::to_string((unsigned char)rd[0]) + "." + std::to_string((unsigned char)rd[1]) + "." +
	       std::to_string((unsigned char)rd[2]) + "." + std::to_string((unsigned char)rd[3]);
}

} // namespace

std::string ServiceInstance::label() const
{
	size_t p = name.find("._");
	return p == std::string::npos ? name : name.substr(0, p);
}

std::string mdns_encode_name(const std::string &name)
{
	std::string out;
	std::string n = name;
	while (!n.empty() && n.front() == '.')
		n.erase(0, 1);
	while (!n.empty() && n.back() == '.')
		n.pop_back();
	size_t pos = 0;
	while (pos <= n.size()) {
		size_t dot = n.find('.', pos);
		std::string label = n.substr(pos, dot == std::string::npos ? std::string::npos : dot - pos);
		out += (char)label.size();
		out += label;
		if (dot == std::string::npos)
			break;
		pos = dot + 1;
	}
	return out + '\0';
}

std::string mdns_build_query(const std::string &name, int qtype, bool unicast)
{
	std::string p(12, '\0');
	p[5] = 1; // QDCOUNT = 1
	p += mdns_encode_name(name);
	p += (char)((qtype >> 8) & 0xFF);
	p += (char)(qtype & 0xFF);
	unsigned qclass = 1 | (unicast ? 0x8000 : 0);
	p += (char)((qclass >> 8) & 0xFF);
	p += (char)(qclass & 0xFF);
	return p;
}

std::vector<ServiceInstance> mdns_parse_response(const std::string &data, const std::string &sender,
						 const std::string &service)
{
	std::vector<std::string> ptrs;
	std::vector<std::pair<std::string, std::pair<std::string, int>>> srv; // nome -> (alvo, porta)
	std::map<std::string, std::vector<std::string>> addrs;
	std::map<std::string, std::map<std::string, std::string>> txts;
	try {
		unsigned qd = u16(data, 4), an = u16(data, 6), ns = u16(data, 8), ar = u16(data, 10);
		size_t pos = 12;
		for (unsigned i = 0; i < qd; ++i) {
			size_t nx;
			read_name(data, pos, nx);
			pos = nx + 4;
		}
		for (unsigned i = 0; i < an + ns + ar; ++i) {
			size_t nx;
			std::string name = read_name(data, pos, nx);
			pos = nx;
			unsigned rtype = u16(data, pos);
			unsigned rdlen = u16(data, pos + 8);
			pos += 10;
			size_t rd_start = pos;
			if (pos + rdlen > data.size())
				throw ParseError("rdata truncado");
			std::string rdata = data.substr(pos, rdlen);
			pos += rdlen;
			std::string lname = lower(name);
			if (rtype == (unsigned)T_PTR) {
				size_t tn;
				std::string target = read_name(data, rd_start, tn);
				if (lname == lower(service))
					ptrs.push_back(target);
			} else if (rtype == (unsigned)T_SRV && rdlen >= 6) {
				int port = (int)u16(data, rd_start + 4);
				size_t tn;
				std::string target = read_name(data, rd_start + 6, tn);
				srv.push_back({name, {target, port}});
			} else if (rtype == (unsigned)T_A && rdlen == 4) {
				addrs[lname].push_back(ipv4_text(rdata));
			} else if (rtype == (unsigned)T_TXT) {
				std::map<std::string, std::string> kv;
				size_t i2 = 0;
				while (i2 < rdata.size()) {
					size_t ln = (unsigned char)rdata[i2];
					std::string item = rdata.substr(i2 + 1, ln);
					i2 += 1 + ln;
					if (!item.empty()) {
						size_t eq = item.find('=');
						if (eq == std::string::npos)
							kv[item] = "";
						else
							kv[item.substr(0, eq)] = item.substr(eq + 1);
					}
				}
				txts[name] = kv;
			}
		}
	} catch (const ParseError &) {
		// devolve o que foi lido ate aqui
	}
	std::vector<ServiceInstance> instances;
	auto get = [&](const std::string &name) -> ServiceInstance & {
		for (auto &i : instances)
			if (i.name == name)
				return i;
		instances.push_back(ServiceInstance());
		instances.back().name = name;
		return instances.back();
	};
	for (const auto &p : ptrs)
		get(p);
	for (const auto &s : srv) {
		if (ends_with(lower(s.first), "." + lower(service))) {
			ServiceInstance &si = get(s.first);
			si.host = s.second.first;
			si.port = s.second.second;
		}
	}
	for (auto &si : instances) {
		auto t = txts.find(si.name);
		if (t != txts.end())
			si.txt = t->second;
		auto a = addrs.find(lower(si.host));
		if (a != addrs.end())
			si.addresses = a->second;
		if (si.addresses.empty() && !sender.empty())
			si.addresses.push_back(sender);
	}
	return instances;
}

std::vector<MdnsEndpoint> mdns_to_endpoints(const std::vector<ServiceInstance> &instances)
{
	std::vector<MdnsEndpoint> out;
	for (const auto &si : instances)
		if (si.port && !si.addresses.empty())
			out.push_back({si.addresses[0], si.port, si.label()});
	return out;
}

} // namespace bdsm
