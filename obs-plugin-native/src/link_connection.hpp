// Conexao com UM celular BDSM: pareamento HTTP (QNetworkAccessManager) +
// WebSocket /ws/link?token= sobre QTcpSocket (o Qt WebSockets nao vem no Qt do
// OBS), com reconexao por backoff e envio de TALLY_UPDATE somente quando muda.
//
// Tudo roda na thread principal (Qt, assincrono): nenhuma thread propria, nenhum
// acesso a API do OBS aqui. O protocolo vive em core/ (testado sem OBS/Qt).
#pragma once

#include <QElapsedTimer>
#include <QObject>
#include <QString>
#include <QTimer>
#include <memory>

#include "link_state.hpp"
#include "link_types.hpp"
#include "pairing_proto.hpp"
#include "secret_store.hpp"
#include "util.hpp"
#include "ws_codec.hpp"

class QNetworkAccessManager;
class QNetworkReply;
class QTcpSocket;

namespace bdsm_qt {

class LinkConnection : public QObject {
	Q_OBJECT
public:
	LinkConnection(const bdsm::Endpoint &ep, bdsm::SettingsStore *store, QNetworkAccessManager *nam,
		       const QString &clientName, QObject *parent = nullptr);
	~LinkConnection() override;

	// Com token salvo: conecta; sem token: fica "Nao pareado".
	void start();
	// Inicia o pareamento (consentimento duplo); ignorado se ja houver um em andamento.
	void requestPair();
	// Cancela pareamento/conexao e apaga o token.
	void forget();
	// Estado de tally desejado: so e enviado quando difere do ultimo enviado.
	void setTally(const QString &state);
	void setAutoRepair(bool on) { autoRepair_ = on; }
	// Encerra tudo (timers, sockets); nao emite mais sinais.
	void stop();

	const bdsm::Endpoint &endpoint() const { return ep_; }

signals:
	void statusChanged(int status, const QString &detail);
	void pairCodeReceived(const QString &code);
	void stateReceived(const bdsm::LinkState &state);
	void tokenInvalid();
	void logMessage(const QString &message);

private:
	// pareamento
	void postPairRequest();
	void schedulePoll();
	void pollPairStatus();
	void onPairOutcome(const bdsm::PairOutcome &out);
	void failPair(const QString &message);
	void cancelPairing();
	QString httpUrl(const QString &path) const;
	void finishReply(QNetworkReply *reply, int gen, bool isRequest);

	// websocket
	void connectWs();
	void onSocketData();
	void onHandshakeData();
	void processIncoming(const std::string &bytes);
	void dropSocket();
	void scheduleReconnect(const QString &why);
	void onUnauthorized(const QString &why);
	void flushTally();
	void setStatus(ConnStatus st, const QString &detail = QString());
	bool sendRaw(const std::string &data);

	bdsm::Endpoint ep_;
	bdsm::SettingsStore *store_;
	QNetworkAccessManager *nam_;
	QString clientName_;
	bool autoRepair_ = true;
	bool stopped_ = false;

	// status (deduplicado)
	bool haveStatus_ = false;
	ConnStatus lastStatus_ = ConnStatus::Disconnected;
	QString lastDetail_;

	// pareamento
	int pairGen_ = 0; // invalida respostas atrasadas ao cancelar
	bool pairing_ = false;
	int rateAttempt_ = 0;
	QString requestId_;
	QElapsedTimer pairClock_;
	int pairDeadlineSec_ = 0;
	QTimer pollTimer_;
	QTimer rateTimer_;

	// websocket
	QTcpSocket *sock_ = nullptr;
	std::string wsKey_;
	std::string hsBuf_;
	bool wsReady_ = false;
	bdsm::WsReceiver rx_;
	bdsm::Backoff backoff_;
	QTimer reconnectTimer_;
	QTimer hsTimer_;
	QTimer idleTimer_;
	QElapsedTimer lastRx_;
	QString desiredTally_;
	QString sentTally_;
};

} // namespace bdsm_qt
