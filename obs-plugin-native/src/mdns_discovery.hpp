// Busca de celulares BDSM por mDNS (_bdsm._tcp) com QUdpSocket. O pacote e o
// parser vivem em core/mdns.*. A consulta sai de uma porta efemera com o bit
// "unicast-response", entao nao disputamos a porta 5353 com o servico mDNS do
// Windows (igual ao script Python).
#pragma once

#include <QObject>
#include <QTimer>
#include <map>
#include <vector>

#include "mdns.hpp"

class QUdpSocket;

namespace bdsm_qt {

class MdnsDiscovery : public QObject {
	Q_OBJECT
public:
	explicit MdnsDiscovery(QObject *parent = nullptr);
	~MdnsDiscovery() override;

	bool running() const { return running_; }
	// Procura por `timeoutMs` (padrao 3 s) e emite finished(). Ignorado se ja estiver rodando.
	void start(int timeoutMs = 3000);

signals:
	void finished(const std::vector<bdsm::MdnsEndpoint> &found);

private:
	void sendAll();
	void onReadyRead();
	void finish();

	QUdpSocket *sock_ = nullptr;
	QTimer resend_;
	QTimer done_;
	bool running_ = false;
	int sent_ = 0;
	std::map<std::string, bdsm::ServiceInstance> found_;
};

} // namespace bdsm_qt
