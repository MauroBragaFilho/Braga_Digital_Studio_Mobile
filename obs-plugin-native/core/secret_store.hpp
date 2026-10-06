// Armazenamento do clientId, dos tokens (protegidos) e dos metadados dos
// celulares (arquivo bdsm_link.json). Porta de bdsm_link/storage.py.
//
// PROTECAO DO TOKEN
//  - Windows: DPAPI (CryptProtectData, escopo do usuario) -> "dpapi:<base64>".
//  - macOS/Linux: *STUB*: "plain:<base64>" em arquivo com chmod 600. NAO e
//    criptografia; o ideal seria Keychain (macOS) / libsecret (Linux). Esses
//    SOs nao sao alvo desta versao (ver README).
// O token NUNCA e logado nem exibido.
#pragma once

#include <optional>
#include <string>
#include <vector>

namespace bdsm {

// `use_dpapi`: -1 = automatico (Windows -> DPAPI), 0 = plain, 1 = DPAPI (so Windows).
std::string protect_secret(const std::string &secret, int use_dpapi = -1); // levanta std::runtime_error se o DPAPI falhar
std::optional<std::string> unprotect_secret(const std::string &blob);      // nullopt se ilegivel (outro usuario/PC)

struct DeviceRecord {
	std::string key;          // "ip:porta"
	std::string token_blob;   // protegido ("" = nao pareado)
	std::string name;         // ultimo nome conhecido
	std::string mapping;      // fonte NDI do OBS escolhida manualmente ("" = automatico)
};

class SettingsStore {
public:
	// `directory` em UTF-8. Cria a pasta ao salvar. `use_dpapi` como em protect_secret.
	explicit SettingsStore(const std::string &directory, int use_dpapi = -1);

	const std::string &directory() const { return dir_; }
	const std::string &path() const { return path_; }
	const std::string &client_id() const { return client_id_; }

	// Dispositivos, na ordem de insercao.
	const std::vector<DeviceRecord> &devices() const { return devices_; }
	bool has_device(const std::string &key) const;
	void add_device(const std::string &key);
	void remove_device(const std::string &key);

	std::optional<std::string> get_token(const std::string &key) const;
	void set_token(const std::string &key, const std::string &token);
	void clear_token(const std::string &key);
	bool has_token(const std::string &key) const;

	std::string mapping(const std::string &key) const;
	void set_mapping(const std::string &key, const std::string &source_name);
	void set_name(const std::string &key, const std::string &name);

	// Persiste. Devolve false se nao conseguiu gravar.
	bool save();

private:
	DeviceRecord *find(const std::string &key);
	const DeviceRecord *find(const std::string &key) const;
	void load();

	std::string dir_, path_, client_id_;
	int use_dpapi_;
	std::vector<DeviceRecord> devices_;
};

std::string generate_client_id(); // "obs-<uuid v4>"

} // namespace bdsm
