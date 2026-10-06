#include "mdns_discovery.hpp"

#include <algorithm>

#include <QHostAddress>
#include <QNetworkInterface>
#include <QUdpSocket>

namespace bdsm_qt {

MdnsDiscovery::MdnsDiscovery(QObject *parent) : QObject(parent)
{
	resend_.setInterval(1000);
	done_.setSingleShot(true);
	connect(&resend_, &QTimer::timeout, this, [this] {
		if (sent_ < 3)
			sendAll();
	});
	connect(&done_, &QTimer::timeout, this, [this] { finish(); });
}

MdnsDiscovery::~MdnsDiscovery()
{
	if (sock_)
		sock_->close();
}

void MdnsDiscovery::start(int timeoutMs)
{
	if (running_)
		return;
	running_ = true;
	sent_ = 0;
	found_.clear();
	sock_ = new QUdpSocket(this);
	if (!sock_->bind(QHostAddress::AnyIPv4, 0)) {
		finish(); // sem socket: devolve lista vazia
		return;
	}
	connect(sock_, &QUdpSocket::readyRead, this, [this] { onReadyRead(); });
	sendAll();
	resend_.start();
	done_.start(timeoutMs);
}

void MdnsDiscovery::sendAll()
{
	if (!sock_)
		return;
	++sent_;
	QByteArray pkt = QByteArray::fromStdString(bdsm::mdns_build_query(bdsm::kMdnsService, bdsm::T_PTR, true));
	const QHostAddress group{QString::fromLatin1(bdsm::kMdnsGroup)};
	bool any = false;
	for (const QNetworkInterface &ni : QNetworkInterface::allInterfaces()) {
		auto fl = ni.flags();
		if (!(fl.testFlag(QNetworkInterface::IsUp) && fl.testFlag(QNetworkInterface::IsRunning) &&
		      fl.testFlag(QNetworkInterface::CanMulticast)) ||
		    fl.testFlag(QNetworkInterface::IsLoopBack))
			continue;
		bool hasV4 = false;
		for (const auto &e : ni.addressEntries())
			if (e.ip().protocol() == QAbstractSocket::IPv4Protocol)
				hasV4 = true;
		if (!hasV4)
			continue;
		sock_->setMulticastInterface(ni);
		sock_->writeDatagram(pkt, group, (quint16)bdsm::kMdnsPort);
		any = true;
	}
	if (!any) // sem interface elegivel: tenta a padrao
		sock_->writeDatagram(pkt, group, (quint16)bdsm::kMdnsPort);
}

void MdnsDiscovery::onReadyRead()
{
	while (sock_ && sock_->hasPendingDatagrams()) {
		QByteArray data;
		data.resize((int)sock_->pendingDatagramSize());
		QHostAddress sender;
		quint16 port = 0;
		sock_->readDatagram(data.data(), data.size(), &sender, &port);
		bool ok = false;
		quint32 v4 = sender.toIPv4Address(&ok);
		std::string senderIp = ok ? QHostAddress(v4).toString().toStdString() : std::string();
		for (auto &si : bdsm::mdns_parse_response(std::string(data.constData(), (size_t)data.size()), senderIp)) {
			auto it = found_.find(si.name);
			if (it == found_.end() || (si.port && !it->second.port)) {
				found_[si.name] = si;
			} else {
				for (const auto &a : si.addresses)
					if (std::find(it->second.addresses.begin(), it->second.addresses.end(), a) ==
					    it->second.addresses.end())
						it->second.addresses.push_back(a);
			}
		}
	}
}

void MdnsDiscovery::finish()
{
	resend_.stop();
	done_.stop();
	if (sock_) {
		sock_->disconnect(this);
		sock_->close();
		sock_->deleteLater();
		sock_ = nullptr;
	}
	running_ = false;
	std::vector<bdsm::ServiceInstance> all;
	for (auto &kv : found_)
		all.push_back(kv.second);
	found_.clear();
	emit finished(bdsm::mdns_to_endpoints(all));
}

} // namespace bdsm_qt
