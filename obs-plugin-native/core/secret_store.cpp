#include "secret_store.hpp"

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <random>
#include <sstream>
#include <stdexcept>

#include "json.hpp"
#include "ws_codec.hpp" // base64

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <wincrypt.h>
#else
#include <sys/stat.h>
#endif

namespace fs = std::filesystem;

namespace bdsm {

namespace {

#ifdef _WIN32
std::string dpapi(const std::string &data, bool protect)
{
	DATA_BLOB in;
	in.cbData = (DWORD)data.size();
	in.pbData = (BYTE *)data.data();
	DATA_BLOB out;
	out.cbData = 0;
	out.pbData = nullptr;
	BOOL ok;
	if (protect)
		ok = CryptProtectData(&in, L"BDSM Link", nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out);
	else
		ok = CryptUnprotectData(&in, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out);
	if (!ok)
		throw std::runtime_error("DPAPI falhou (erro " + std::to_string((unsigned long)GetLastError()) + ")");
	std::string r((const char *)out.pbData, (size_t)out.cbData);
	LocalFree(out.pbData);
	return r;
}
#endif

fs::path utf8_path(const std::string &s) { return fs::u8path(s); }

} // namespace

std::string protect_secret(const std::string &secret, int use_dpapi)
{
#ifdef _WIN32
	bool dp = use_dpapi != 0;
#else
	bool dp = false;
	(void)use_dpapi;
#endif
	if (dp) {
#ifdef _WIN32
		return "dpapi:" + base64_encode(dpapi(secret, true));
#endif
	}
	return "plain:" + base64_encode(secret);
}

std::optional<std::string> unprotect_secret(const std::string &blob)
{
	try {
		size_t c = blob.find(':');
		if (c == std::string::npos)
			return std::nullopt;
		std::string scheme = blob.substr(0, c), raw;
		if (!base64_decode(blob.substr(c + 1), raw))
			return std::nullopt;
		if (scheme == "plain")
			return raw;
#ifdef _WIN32
		if (scheme == "dpapi")
			return dpapi(raw, false);
#endif
	} catch (...) {
	}
	return std::nullopt;
}

std::string generate_client_id()
{
	static std::random_device rd;
	unsigned char b[16];
	for (auto &x : b)
		x = (unsigned char)(rd() & 0xFF);
	b[6] = (unsigned char)((b[6] & 0x0F) | 0x40);
	b[8] = (unsigned char)((b[8] & 0x3F) | 0x80);
	static const char *hex = "0123456789abcdef";
	std::string s = "obs-";
	for (int i = 0; i < 16; ++i) {
		if (i == 4 || i == 6 || i == 8 || i == 10)
			s += '-';
		s += hex[b[i] >> 4];
		s += hex[b[i] & 15];
	}
	return s;
}

SettingsStore::SettingsStore(const std::string &directory, int use_dpapi) : dir_(directory), use_dpapi_(use_dpapi)
{
	path_ = (utf8_path(dir_) / "bdsm_link.json").u8string();
	load();
	if (client_id_.empty()) {
		client_id_ = generate_client_id();
		save();
	}
}

void SettingsStore::load()
{
	std::ifstream f(utf8_path(path_), std::ios::binary);
	if (!f)
		return;
	std::stringstream ss;
	ss << f.rdbuf();
	Json j;
	if (!parse_json(ss.str(), j) || !j.is_object())
		return; // arquivo corrompido: recomeca limpo
	if (const Json *c = j.find("clientId"))
		if (c->is_string())
			client_id_ = c->str;
	const Json *devs = j.find("devices");
	if (devs && devs->is_object()) {
		for (size_t i = 0; i < devs->keys.size(); ++i) {
			const Json &d = devs->items[i];
			if (!d.is_object())
				continue;
			DeviceRecord r;
			r.key = devs->keys[i];
			if (const Json *t = d.find("token"))
				if (t->is_string())
					r.token_blob = t->str;
			if (const Json *n = d.find("name"))
				if (n->is_string())
					r.name = n->str;
			if (const Json *m = d.find("mapping"))
				if (m->is_string())
					r.mapping = m->str;
			devices_.push_back(std::move(r));
		}
	}
}

bool SettingsStore::save()
{
	std::string out = "{\n  \"clientId\": " + json_quote(client_id_) + ",\n  \"devices\": {";
	for (size_t i = 0; i < devices_.size(); ++i) {
		const auto &d = devices_[i];
		out += (i ? ",\n    " : "\n    ") + json_quote(d.key) + ": {";
		out += "\"token\": " + json_quote(d.token_blob);
		out += ", \"name\": " + json_quote(d.name);
		out += ", \"mapping\": " + json_quote(d.mapping) + "}";
	}
	out += devices_.empty() ? "}\n}\n" : "\n  }\n}\n";
	try {
		fs::create_directories(utf8_path(dir_));
		fs::path tmp = utf8_path(path_ + ".tmp");
		{
			std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
			if (!f)
				return false;
			f << out;
			if (!f.good())
				return false;
		}
#ifndef _WIN32
		chmod(tmp.string().c_str(), 0600); // no Windows a protecao e o DPAPI
#endif
		fs::rename(tmp, utf8_path(path_));
		return true;
	} catch (...) {
		return false;
	}
}

DeviceRecord *SettingsStore::find(const std::string &key)
{
	for (auto &d : devices_)
		if (d.key == key)
			return &d;
	return nullptr;
}
const DeviceRecord *SettingsStore::find(const std::string &key) const
{
	for (const auto &d : devices_)
		if (d.key == key)
			return &d;
	return nullptr;
}

bool SettingsStore::has_device(const std::string &key) const { return find(key) != nullptr; }

void SettingsStore::add_device(const std::string &key)
{
	if (find(key))
		return;
	DeviceRecord r;
	r.key = key;
	devices_.push_back(r);
	save();
}

void SettingsStore::remove_device(const std::string &key)
{
	auto it = std::remove_if(devices_.begin(), devices_.end(), [&](const DeviceRecord &d) { return d.key == key; });
	if (it != devices_.end()) {
		devices_.erase(it, devices_.end());
		save();
	}
}

std::optional<std::string> SettingsStore::get_token(const std::string &key) const
{
	const DeviceRecord *d = find(key);
	if (!d || d->token_blob.empty())
		return std::nullopt;
	return unprotect_secret(d->token_blob);
}

void SettingsStore::set_token(const std::string &key, const std::string &token)
{
	DeviceRecord *d = find(key);
	if (!d) {
		add_device(key);
		d = find(key);
	}
	d->token_blob = protect_secret(token, use_dpapi_);
	save();
}

void SettingsStore::clear_token(const std::string &key)
{
	DeviceRecord *d = find(key);
	if (d && !d->token_blob.empty()) {
		d->token_blob.clear();
		save();
	}
}

bool SettingsStore::has_token(const std::string &key) const
{
	const DeviceRecord *d = find(key);
	return d && !d->token_blob.empty();
}

std::string SettingsStore::mapping(const std::string &key) const
{
	const DeviceRecord *d = find(key);
	return d ? d->mapping : std::string();
}

void SettingsStore::set_mapping(const std::string &key, const std::string &source_name)
{
	DeviceRecord *d = find(key);
	if (d && d->mapping != source_name) {
		d->mapping = source_name;
		save();
	}
}

void SettingsStore::set_name(const std::string &key, const std::string &name)
{
	DeviceRecord *d = find(key);
	if (d && d->name != name) {
		d->name = name;
		save();
	}
}

} // namespace bdsm
