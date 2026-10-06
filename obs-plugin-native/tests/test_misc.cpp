// mDNS, pareamento (protocolo), JSON e armazenamento (SettingsStore / DPAPI).
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <sstream>

#include "json.hpp"
#include "mdns.hpp"
#include "minitest.hpp"
#include "pairing_proto.hpp"
#include "secret_store.hpp"
#include "util.hpp"

using namespace bdsm;

// ------------------------------------------------------------------ mDNS
static std::string be16(unsigned v)
{
	std::string s;
	s += (char)((v >> 8) & 0xFF);
	s += (char)(v & 0xFF);
	return s;
}
static std::string be32(unsigned long v) { return be16((unsigned)(v >> 16)) + be16((unsigned)(v & 0xFFFF)); }

static std::string rr(const std::string &name_bytes, int rtype, const std::string &rdata, unsigned long ttl = 120)
{
	return name_bytes + be16((unsigned)rtype) + be16(0x8001) + be32(ttl) + be16((unsigned)rdata.size()) + rdata;
}

// Reproduz build_mdns_response() de test_unit.py (PTR + SRV + TXT + A, com compressao).
static std::string build_mdns_response(const std::string &instance = "BDSM Link", int port = 8080,
				       const std::string &ip4 = std::string("\xC0\xA8\x00\x14", 4), // 192.168.0.20
				       const std::string &host = "android-1a2b.local", bool with_a = true,
				       const std::string &svc = "_bdsm._tcp.local")
{
	std::string body;
	int count = 0;
	std::string ptr_name = mdns_encode_name(svc);
	std::string inst_label = std::string(1, (char)instance.size()) + instance;
	size_t header_len = 12;
	std::string rdata_ptr = inst_label + be16(0xC000 | (unsigned)header_len);
	body += rr(ptr_name, T_PTR, rdata_ptr);
	++count;
	size_t inst_off = header_len + ptr_name.size() + 10;
	std::string inst_name_ptr = be16(0xC000 | (unsigned)inst_off);
	std::string srv_rdata = be16(0) + be16(0) + be16((unsigned)port) + mdns_encode_name(host);
	body += rr(inst_name_ptr, T_SRV, srv_rdata);
	++count;
	std::string txt;
	for (std::string x : {std::string("ver=1"), std::string("modelo=A51")})
		txt += std::string(1, (char)x.size()) + x;
	body += rr(inst_name_ptr, T_TXT, txt);
	++count;
	if (with_a) {
		body += rr(mdns_encode_name(host), T_A, ip4);
		++count;
	}
	std::string header = be16(0) + be16(0x8400) + be16(0) + be16(1) + be16(0) + be16((unsigned)(count - 1));
	return header + body;
}

TEST(mdns_build_query)
{
	std::string q = mdns_build_query();
	CHECK_EQ(q.size(), (size_t)(12 + mdns_encode_name(kMdnsService).size() + 4));
	CHECK_EQ((unsigned char)q[5], 1u); // QDCOUNT
	CHECK_EQ(q.substr(12, mdns_encode_name(kMdnsService).size()), mdns_encode_name(kMdnsService));
	CHECK_EQ(((unsigned char)q[q.size() - 4] << 8) | (unsigned char)q[q.size() - 3], T_PTR);
	CHECK_EQ(((unsigned char)q[q.size() - 2] << 8) | (unsigned char)q[q.size() - 1], 1);
	std::string u = mdns_build_query(kMdnsService, T_PTR, true);
	CHECK_EQ(((unsigned char)u[u.size() - 2] << 8) | (unsigned char)u[u.size() - 1], 0x8001);
}

TEST(mdns_parse_full_response)
{
	auto found = mdns_parse_response(build_mdns_response(), "192.168.0.99");
	CHECK_EQ(found.size(), (size_t)1);
	const auto &si = found[0];
	CHECK_EQ(si.name, "BDSM Link._bdsm._tcp.local");
	CHECK_EQ(si.label(), "BDSM Link");
	CHECK_EQ(si.host, "android-1a2b.local");
	CHECK_EQ(si.port, 8080);
	CHECK_EQ(si.addresses.size(), (size_t)1);
	CHECK_EQ(si.addresses[0], "192.168.0.20");
	CHECK_EQ(si.txt.at("ver"), "1");
	CHECK_EQ(si.txt.at("modelo"), "A51");
	auto eps = mdns_to_endpoints(found);
	CHECK_EQ(eps.size(), (size_t)1);
	CHECK_EQ(eps[0].ip, "192.168.0.20");
	CHECK_EQ(eps[0].port, 8080);
	CHECK_EQ(eps[0].label, "BDSM Link");
}

TEST(mdns_missing_a_record_falls_back_to_sender)
{
	auto found = mdns_parse_response(build_mdns_response("BDSM Link", 8080, "", "android-1a2b.local", false), "10.1.1.7");
	CHECK_EQ(found.size(), (size_t)1);
	CHECK_EQ(found[0].addresses.size(), (size_t)1);
	CHECK_EQ(found[0].addresses[0], "10.1.1.7");
}

TEST(mdns_other_services_ignored_and_garbage_tolerated)
{
	auto other = mdns_parse_response(build_mdns_response("BDSM Link", 8080, std::string("\xC0\xA8\x00\x14", 4), "android-1a2b.local", true, "_xxxx._tcp.local"));
	CHECK(other.empty());
	CHECK(mdns_parse_response(std::string("\x00\x01", 2)).empty());
	std::string junk;
	for (int i = 0; i < 64; ++i)
		junk += (char)(i * 37 + 11);
	(void)mdns_parse_response(junk); // nao levanta
	(void)mdns_parse_response(build_mdns_response().substr(0, 40));
}

TEST(mdns_compression_loop_does_not_hang)
{
	std::string evil = be16(0) + be16(0x8400) + be16(1) + be16(0) + be16(0) + be16(0) +
			   std::string("\xC0\x0C\x00\x01\x00\x01", 6);
	CHECK(mdns_parse_response(evil).empty());
}

// ------------------------------------------------------------------ pareamento
TEST(pair_body_and_limits)
{
	std::string b = build_pair_request_body("obs-123", "OBS Studio (PC)");
	CHECK_EQ(b, "{\"clientId\":\"obs-123\",\"clientName\":\"OBS Studio (PC)\"}");
	std::string longName = build_pair_request_body("id", std::string(100, 'x'));
	CHECK(longName.size() < kPairMaxBodyBytes);
	Json j;
	CHECK(parse_json(longName, j));
	CHECK_EQ(j.find("clientName")->str.size(), (size_t)40);
	// aspas e barras sao escapadas
	std::string esc = build_pair_request_body("a\"b", "c\\d");
	Json j2;
	CHECK(parse_json(esc, j2));
	CHECK_EQ(j2.find("clientId")->str, "a\"b");
	CHECK_EQ(j2.find("clientName")->str, "c\\d");
}

TEST(pair_request_responses)
{
	PairRequestInfo info;
	PairError err;
	CHECK(parse_pair_request_response(200, "{\"requestId\":\"r1\",\"code\":\"0427\",\"expiresInSec\":90}", info, err));
	CHECK_EQ(info.request_id, "r1");
	CHECK_EQ(info.code, "0427"); // zero a esquerda preservado
	CHECK_EQ(info.expires_in_sec, 90);
	CHECK(!parse_pair_request_response(400, "", info, err));
	CHECK(err.kind == PairErrorKind::BadRequest);
	CHECK(!parse_pair_request_response(413, "", info, err));
	CHECK(err.kind == PairErrorKind::TooLarge);
	CHECK(!parse_pair_request_response(429, "", info, err));
	CHECK(err.kind == PairErrorKind::RateLimited);
	CHECK_EQ(err.http_status, 429);
	CHECK(!parse_pair_request_response(500, "", info, err));
	CHECK(err.kind == PairErrorKind::Protocol);
	CHECK(!parse_pair_request_response(200, "nao json", info, err));
	CHECK(err.kind == PairErrorKind::Protocol);
	CHECK(!parse_pair_request_response(200, "{\"code\":\"1234\"}", info, err)); // sem requestId
}

TEST(pair_status_responses)
{
	PairOutcome out;
	PairError err;
	CHECK(parse_pair_status_response(200, "{\"state\":\"PENDING\"}", out, err));
	CHECK_EQ(out.state, "PENDING");
	CHECK(!out.token.has_value());
	CHECK(parse_pair_status_response(200, "{\"state\":\"approved\",\"token\":\"" + std::string(64, 'a') + "\"}", out, err));
	CHECK_EQ(out.state, "APPROVED");
	CHECK(out.token.has_value());
	CHECK_EQ(out.token->size(), (size_t)64);
	CHECK(parse_pair_status_response(200, "{\"state\":\"APPROVED\"}", out, err)); // token ja consumido
	CHECK(!out.token.has_value());
	CHECK(parse_pair_status_response(200, "{\"state\":\"DENIED\"}", out, err));
	CHECK_EQ(out.state, "DENIED");
	CHECK(parse_pair_status_response(404, "", out, err)); // 404 = expirou / outro IP
	CHECK_EQ(out.state, "EXPIRED");
	CHECK(!parse_pair_status_response(500, "", out, err));
	CHECK(!parse_pair_status_response(200, "{}", out, err));
}

TEST(pair_rate_limit_delays_and_url_encode)
{
	CHECK_EQ(rate_limit_delay(0), 6.0);
	CHECK_EQ(rate_limit_delay(1), 12.0);
	CHECK_EQ(rate_limit_delay(2), 24.0);
	CHECK_EQ(rate_limit_delay(9), 24.0);
	CHECK_EQ(url_encode(std::string(64, 'f')), std::string(64, 'f'));
	CHECK_EQ(url_encode("a b&c=d/e"), "a%20b%26c%3Dd%2Fe");
}

// ------------------------------------------------------------------ JSON
TEST(json_parse_basics)
{
	Json j;
	CHECK(parse_json("  {\"a\":[1,2.5,-3e2,true,false,null,\"x\\n\\u00e9\\ud83d\\ude00\"],\"b\":{}}  ", j));
	CHECK(j.is_object());
	const Json *a = j.find("a");
	CHECK(a && a->is_array());
	CHECK_EQ(a->items.size(), (size_t)7);
	CHECK_EQ(a->items[1].number, 2.5);
	CHECK_EQ(a->items[2].number, -300.0);
	CHECK(a->items[3].boolean);
	CHECK(a->items[5].is_null());
	CHECK_EQ(a->items[6].str, "x\n\xC3\xA9\xF0\x9F\x98\x80"); // \u00e9 e par substituto -> UTF-8
}

TEST(json_rejects_garbage)
{
	Json j;
	for (const char *bad : {"", "{", "[1,]", "{\"a\"}", "{\"a\":1} x", "\"abc", "01", "1.", "tru", "{'a':1}", "\"\\x\"",
				"\"\\ud83d\""}) {
		CHECK(!parse_json(bad, j));
	}
	std::string deep(200, '[');
	CHECK(!parse_json(deep, j)); // profundidade
}

TEST(json_quote_roundtrip)
{
	std::string s = std::string("a\"b\\c\n\t") + '\x01' + "\xC3\xA7";
	Json j;
	CHECK(parse_json(json_quote(s), j));
	CHECK_EQ(j.str, s);
}

// ------------------------------------------------------------------ armazenamento
namespace fs = std::filesystem;

static std::string temp_dir(const std::string &name)
{
	fs::path p = fs::temp_directory_path() / ("bdsm_link_test_" + name + "_" + std::to_string(std::rand()));
	fs::create_directories(p);
	return p.string();
}

static std::string read_all(const std::string &path)
{
	std::ifstream f(path, std::ios::binary);
	std::stringstream ss;
	ss << f.rdbuf();
	return ss.str();
}

TEST(protect_roundtrip)
{
	std::string tok(64, 'a');
	for (int i = 0; i < 64; ++i)
		tok[(size_t)i] = "0123456789abcdef"[i % 16];
	std::string blob = protect_secret(tok);
	CHECK(blob.find(tok) == std::string::npos);
	CHECK(unprotect_secret(blob).value_or("") == tok);
#ifdef _WIN32
	CHECK(blob.rfind("dpapi:", 0) == 0);
#endif
}

TEST(plain_fallback_roundtrip)
{
	std::string blob = protect_secret("tok", 0);
	CHECK(blob.rfind("plain:", 0) == 0);
	CHECK(unprotect_secret(blob).value_or("") == "tok");
}

TEST(garbage_returns_nullopt)
{
	CHECK(!unprotect_secret("dpapi:@@@").has_value());
	CHECK(!unprotect_secret("nada").has_value());
	CHECK(!unprotect_secret("dpapi:QUJD").has_value()); // base64 valido mas nao e blob DPAPI
	CHECK(!unprotect_secret("").has_value());
}

TEST(store_persists_and_never_writes_plain_token)
{
	std::string d = temp_dir("persist");
	std::string tok(64, 'c');
	std::string cid;
	{
		SettingsStore s1(d);
		cid = s1.client_id();
		CHECK(cid.rfind("obs-", 0) == 0);
		CHECK_EQ(cid.size(), (size_t)(4 + 36));
		s1.add_device("1.2.3.4:8080");
		s1.set_token("1.2.3.4:8080", tok);
		s1.set_mapping("1.2.3.4:8080", "Cam 1");
		s1.set_name("1.2.3.4:8080", "Cel \xC3\xA7");
		CHECK(read_all(s1.path()).find(tok) == std::string::npos);
	}
	{
		SettingsStore s2(d);
		CHECK_EQ(s2.client_id(), cid); // clientId estavel
		CHECK(s2.has_device("1.2.3.4:8080"));
		CHECK(s2.get_token("1.2.3.4:8080").value_or("") == tok);
		CHECK_EQ(s2.mapping("1.2.3.4:8080"), "Cam 1");
		CHECK_EQ(s2.devices().size(), (size_t)1);
		CHECK_EQ(s2.devices()[0].name, "Cel \xC3\xA7");
		s2.clear_token("1.2.3.4:8080");
	}
	{
		SettingsStore s3(d);
		CHECK(!s3.get_token("1.2.3.4:8080").has_value());
		CHECK(s3.has_device("1.2.3.4:8080")); // continua na lista, so sem token
		s3.remove_device("1.2.3.4:8080");
	}
	SettingsStore s4(d);
	CHECK(s4.devices().empty());
	fs::remove_all(d);
}

TEST(store_corrupted_file_recovers)
{
	std::string d = temp_dir("corrupt");
	{
		std::ofstream f(fs::path(d) / "bdsm_link.json");
		f << "{nao e json";
	}
	SettingsStore s(d);
	CHECK(!s.client_id().empty());
	CHECK(!s.get_token("x:1").has_value());
	fs::remove_all(d);
}

TEST(store_keeps_device_order_and_unknown_key_safe)
{
	std::string d = temp_dir("order");
	SettingsStore s(d);
	s.add_device("10.0.0.3:8080");
	s.add_device("10.0.0.1:8080");
	s.add_device("10.0.0.3:8080"); // duplicado ignorado
	CHECK_EQ(s.devices().size(), (size_t)2);
	CHECK_EQ(s.devices()[0].key, "10.0.0.3:8080");
	s.set_mapping("nao-existe:1", "x"); // nao deve falhar
	CHECK(!s.get_token("nao-existe:1").has_value());
	fs::remove_all(d);
}
