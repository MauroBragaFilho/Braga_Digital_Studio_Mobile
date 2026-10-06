#include "device_manager.hpp"

#include <QNetworkProxy>
#include <algorithm>

namespace bdsm_qt {

QString Device::displayName() const
{
	return state ? QString::fromStdString(state->device_name) : QString::fromStdString(key);
}

DeviceManager::DeviceManager(const std::string &configDir, const QString &hostName, QObject *parent)
	: QObject(parent),
	  store_(new bdsm::SettingsStore(configDir)),
	  clientName_(QString::fromStdString(bdsm::make_client_name(hostName.toStdString())))
{
	nam_.setProxy(QNetworkProxy::NoProxy); // celular na LAN: nunca por proxy
}

DeviceManager::~DeviceManager() { shutdown(); }

void DeviceManager::shutdown()
{
	if (down_)
		return;
	down_ = true;
	for (auto &kv : devices_) {
		if (kv.second->conn) {
			// Deixa o celular sem borda vermelha/verde: o fechamento gracioso do socket
			// (LinkConnection::dropSocket) descarrega o que ja foi escrito.
			kv.second->conn->setTally(QStringLiteral("OFF"));
			kv.second->conn->stop();
		}
	}
}

void DeviceManager::load()
{
	for (const auto &rec : store_->devices()) {
		bdsm::Endpoint ep;
		if (!bdsm::parse_endpoint(rec.key, ep))
			continue; // entrada corrompida no arquivo: ignora
		createDevice(rec.key, ep);
	}
	for (const auto &key : order_)
		if (Device *d = find(key))
			d->conn->start();
	emit changed();
}

Device *DeviceManager::find(const std::string &key)
{
	auto it = devices_.find(key);
	return it == devices_.end() ? nullptr : it->second.get();
}

const Device *DeviceManager::device(const std::string &key) const
{
	auto it = devices_.find(key);
	return it == devices_.end() ? nullptr : it->second.get();
}

std::vector<const Device *> DeviceManager::devices() const
{
	std::vector<const Device *> out;
	for (const auto &k : order_) {
		auto it = devices_.find(k);
		if (it != devices_.end())
			out.push_back(it->second.get());
	}
	return out;
}

int DeviceManager::connectedCount() const
{
	int n = 0;
	for (const auto &kv : devices_)
		if (kv.second->status == ConnStatus::Connected)
			++n;
	return n;
}

int DeviceManager::onAirCount() const
{
	int n = 0;
	for (const auto &kv : devices_)
		if (kv.second->status == ConnStatus::Connected && kv.second->tally == "PROGRAM")
			++n;
	return n;
}

void DeviceManager::createDevice(const std::string &key, const bdsm::Endpoint &ep)
{
	auto d = std::make_unique<Device>();
	d->key = key;
	d->ep = ep;
	d->mapping = store_->mapping(key);
	d->conn = new LinkConnection(ep, store_.get(), &nam_, clientName_, this);
	d->conn->setAutoRepair(autoRepair_);
	connect(d->conn, &LinkConnection::statusChanged, this,
		[this, key](int st, const QString &detail) { onStatus(key, st, detail); });
	connect(d->conn, &LinkConnection::pairCodeReceived, this, [this, key](const QString &code) {
		if (Device *dev = find(key))
			dev->pairCode = code;
		// O codigo tambem vai para o log, para conferir com o celular (nunca o token).
		emit logMessage(QStringLiteral("Codigo de pareamento para %1: %2 (confira no celular e aprove)")
					.arg(QString::fromStdString(key), code));
		emit notice(QStringLiteral("Pareamento %1: codigo %2").arg(QString::fromStdString(key), code), false);
		emit changed();
	});
	connect(d->conn, &LinkConnection::stateReceived, this,
		[this, key](const bdsm::LinkState &st) { onState(key, st); });
	connect(d->conn, &LinkConnection::tokenInvalid, this, [this, key] {
		if (Device *dev = find(key))
			dev->state.reset();
		emit notice(QStringLiteral("Pareamento de %1 foi revogado/recusado (401)").arg(QString::fromStdString(key)), true);
		emit changed();
	});
	connect(d->conn, &LinkConnection::logMessage, this, &DeviceManager::logMessage);
	order_.push_back(key);
	devices_[key] = std::move(d);
}

bool DeviceManager::addDevice(const QString &text, QString *error)
{
	bdsm::Endpoint ep;
	if (!bdsm::parse_endpoint(text.toStdString(), ep)) {
		if (error)
			*error = QStringLiteral("Endereco invalido: %1").arg(text);
		return false;
	}
	std::string key = ep.key();
	if (devices_.count(key))
		return true; // ja existe
	store_->add_device(key);
	createDevice(key, ep);
	if (Device *d = find(key))
		d->conn->start();
	emit changed();
	emit tallyInputsChanged();
	return true;
}

void DeviceManager::removeDevice(const std::string &key)
{
	auto it = devices_.find(key);
	if (it == devices_.end())
		return;
	if (it->second->conn) {
		it->second->conn->setTally(QStringLiteral("OFF")); // nao deixa o celular "no ar" por engano
		it->second->conn->stop();
		it->second->conn->deleteLater();
	}
	devices_.erase(it);
	order_.erase(std::remove(order_.begin(), order_.end(), key), order_.end());
	store_->remove_device(key); // apaga tambem o token salvo
	emit changed();
}

void DeviceManager::pair(const std::string &key)
{
	if (Device *d = find(key)) {
		d->pairCode.clear();
		d->conn->requestPair();
	}
}

void DeviceManager::forget(const std::string &key)
{
	if (Device *d = find(key)) {
		d->conn->forget();
		d->state.reset();
		d->pairCode.clear();
		emit changed();
	}
}

void DeviceManager::setMapping(const std::string &key, const std::string &sourceName)
{
	Device *d = find(key);
	if (!d || d->mapping == sourceName)
		return;
	d->mapping = sourceName;
	store_->set_mapping(key, sourceName);
	emit changed();
	emit tallyInputsChanged();
}

std::vector<bdsm::TallyTarget> DeviceManager::tallyTargets() const
{
	std::vector<bdsm::TallyTarget> out;
	for (const auto &k : order_) {
		const Device *d = device(k);
		if (d)
			out.push_back(bdsm::TallyTarget{d->key, d->ndiStreamName(), d->mapping});
	}
	return out;
}

void DeviceManager::applyTally(const std::map<std::string, std::string> &computed)
{
	bool any = false;
	for (const auto &kv : computed) {
		Device *d = find(kv.first);
		if (!d)
			continue;
		if (d->tally != kv.second) {
			d->tally = kv.second;
			any = true;
		}
		d->conn->setTally(QString::fromStdString(kv.second)); // so envia quando difere do ultimo enviado
	}
	if (any)
		emit changed();
}

void DeviceManager::onStatus(const std::string &key, int status, const QString &detail)
{
	Device *d = find(key);
	if (!d)
		return;
	d->status = (ConnStatus)status;
	d->detail = detail;
	if (d->status != ConnStatus::Pending)
		d->pairCode.clear();
	if (d->status == ConnStatus::Unpaired || d->status == ConnStatus::Error)
		d->state.reset();
	else if (d->status == ConnStatus::Pending && !detail.isEmpty() && detail.size() == 4)
		d->pairCode = detail; // no estado Pending o detalhe e o codigo de 4 digitos
	emit changed();
}

void DeviceManager::onState(const std::string &key, const bdsm::LinkState &st)
{
	Device *d = find(key);
	if (!d)
		return;
	// O 1o quadro pode vir antes do coletor do app preencher a telemetria: ignora.
	if (st.is_placeholder())
		return;
	auto old = d->ndiStreamName();
	d->state = st;
	store_->set_name(key, st.device_name);
	if (old != st.ndi_stream_name)
		emit tallyInputsChanged();
	if (d->battery.update(st.battery_level)) {
		QString msg = QStringLiteral("Bateria baixa em %1: %2%").arg(d->displayName()).arg(st.battery_level);
		emit logMessage(msg);
		emit notice(msg, true);
	}
	emit changed();
}

} // namespace bdsm_qt
