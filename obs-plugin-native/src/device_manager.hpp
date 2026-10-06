// Gerencia a lista de celulares BDSM: persistencia (SettingsStore), uma
// LinkConnection por celular, telemetria atual, alerta de bateria e tally.
// Thread principal (Qt). Sem acesso a API do OBS (o tally calculado chega por
// applyTally()).
#pragma once

#include <QNetworkAccessManager>
#include <QObject>
#include <QString>
#include <QStringList>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "link_connection.hpp"
#include "link_state.hpp"
#include "link_types.hpp"
#include "secret_store.hpp"
#include "tally.hpp"
#include "util.hpp"

namespace bdsm_qt {

struct Device {
	std::string key;
	bdsm::Endpoint ep;
	std::string mapping;       // fonte NDI do OBS escolhida manualmente ("" = automatico)
	ConnStatus status = ConnStatus::Disconnected;
	QString detail;
	QString pairCode;
	std::optional<bdsm::LinkState> state;
	std::string tally = "OFF"; // ultimo tally calculado/enviado
	bdsm::BatteryAlert battery;
	LinkConnection *conn = nullptr;

	QString displayName() const;
	std::optional<std::string> ndiStreamName() const { return state ? state->ndi_stream_name : std::nullopt; }
};

class DeviceManager : public QObject {
	Q_OBJECT
public:
	// `configDir` em UTF-8 (obs_module_config_path); `hostName` para o nome do cliente.
	DeviceManager(const std::string &configDir, const QString &hostName, QObject *parent = nullptr);
	~DeviceManager() override;

	// Cria as conexoes dos celulares salvos.
	void load();
	// Encerra tudo (sockets, timers). Idempotente. Usado em OBS_FRONTEND_EVENT_EXIT.
	void shutdown();

	// Dispositivos na ordem de insercao.
	std::vector<const Device *> devices() const;
	const Device *device(const std::string &key) const;
	int connectedCount() const;
	// Celulares conectados cujo tally e PROGRAM ("no ar").
	int onAirCount() const;

	// "IP[:porta]". Devolve false se o texto for invalido.
	bool addDevice(const QString &text, QString *error = nullptr);
	void removeDevice(const std::string &key);
	void pair(const std::string &key);
	void forget(const std::string &key);
	void setMapping(const std::string &key, const std::string &sourceName);

	// Alvos do calculo de tally e resultado.
	std::vector<bdsm::TallyTarget> tallyTargets() const;
	void applyTally(const std::map<std::string, std::string> &computed);

	bool autoRepair() const { return autoRepair_; }

signals:
	void changed();                                  // algo mudou na lista/estado (coalescido pela UI)
	void tallyInputsChanged();                       // ndiStreamName/mapeamento mudou: recalcular tally
	void notice(const QString &message, bool warning); // alerta (bateria baixa, revogado...)
	void logMessage(const QString &message);

private:
	Device *find(const std::string &key);
	void createDevice(const std::string &key, const bdsm::Endpoint &ep);
	void onStatus(const std::string &key, int status, const QString &detail);
	void onState(const std::string &key, const bdsm::LinkState &st);

	std::unique_ptr<bdsm::SettingsStore> store_;
	QString clientName_;
	QNetworkAccessManager nam_;
	std::vector<std::string> order_;
	std::map<std::string, std::unique_ptr<Device>> devices_;
	bool autoRepair_ = true;
	bool down_ = false;
};

} // namespace bdsm_qt
