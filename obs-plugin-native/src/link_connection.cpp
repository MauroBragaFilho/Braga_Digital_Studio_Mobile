#include "link_connection.hpp"

#include <chrono>

#include <QAbstractSocket>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QTcpSocket>
#include <QUrl>

namespace bdsm_qt {

namespace {
constexpr int kHttpTimeoutMs = 5000;
constexpr int kHandshakeTimeoutMs = 5000;
constexpr int kIdleTimeoutMs = 15000; // sem mensagens ha 15 s -> reconecta (celular publica a 2 Hz)
} // namespace

LinkConnection::LinkConnection(const bdsm::Endpoint &ep, bdsm::SettingsStore *store, QNetworkAccessManager *nam,
			       const QString &clientName, QObject *parent)
	: QObject(parent), ep_(ep), store_(store), nam_(nam), clientName_(clientName)
{
	pollTimer_.setSingleShot(true);
	rateTimer_.setSingleShot(true);
	reconnectTimer_.setSingleShot(true);
	hsTimer_.setSingleShot(true);
	hsTimer_.setInterval(kHandshakeTimeoutMs);
	idleTimer_.setInterval(1000);

	connect(&pollTimer_, &QTimer::timeout, this, [this] { pollPairStatus(); });
	connect(&rateTimer_, &QTimer::timeout, this, [this] {
		if (pairing_ && !stopped_)
			postPairRequest();
	});
	connect(&reconnectTimer_, &QTimer::timeout, this, [this] { connectWs(); });
	connect(&hsTimer_, &QTimer::timeout, this, [this] { scheduleReconnect(QStringLiteral("tempo esgotado ao conectar")); });
	connect(&idleTimer_, &QTimer::timeout, this, [this] {
		if (wsReady_ && lastRx_.isValid() && lastRx_.elapsed() > kIdleTimeoutMs)
			scheduleReconnect(QStringLiteral("sem dados ha %1 s").arg(kIdleTimeoutMs / 1000));
	});
}

LinkConnection::~LinkConnection() { stop(); }

QString LinkConnection::httpUrl(const QString &path) const
{
	return QStringLiteral("http://%1:%2%3").arg(QString::fromStdString(ep_.host)).arg(ep_.port).arg(path);
}

void LinkConnection::setStatus(ConnStatus st, const QString &detail)
{
	if (stopped_)
		return;
	if (haveStatus_ && st == lastStatus_ && detail == lastDetail_)
		return; // evita inundar a UI com o mesmo estado
	haveStatus_ = true;
	lastStatus_ = st;
	lastDetail_ = detail;
	emit statusChanged((int)st, detail);
}

// ------------------------------------------------------------------ ciclo de vida
void LinkConnection::start()
{
	if (stopped_)
		return;
	if (store_->has_token(ep_.key()))
		connectWs();
	else
		setStatus(ConnStatus::Unpaired);
}

void LinkConnection::stop()
{
	stopped_ = true;
	cancelPairing();
	reconnectTimer_.stop();
	dropSocket();
}

void LinkConnection::forget()
{
	if (stopped_)
		return;
	cancelPairing();
	reconnectTimer_.stop();
	dropSocket();
	sentTally_.clear();
	store_->clear_token(ep_.key());
	setStatus(ConnStatus::Unpaired);
}

void LinkConnection::setTally(const QString &state)
{
	desiredTally_ = state;
	flushTally();
}

// ------------------------------------------------------------------ pareamento
void LinkConnection::requestPair()
{
	if (stopped_ || pairing_)
		return;
	reconnectTimer_.stop();
	dropSocket();
	sentTally_.clear();
	pairing_ = true;
	rateAttempt_ = 0;
	requestId_.clear();
	++pairGen_;
	setStatus(ConnStatus::Pending, QStringLiteral("Enviando pedido..."));
	postPairRequest();
}

void LinkConnection::cancelPairing()
{
	++pairGen_; // respostas em voo passam a ser ignoradas
	pairing_ = false;
	pollTimer_.stop();
	rateTimer_.stop();
}

void LinkConnection::postPairRequest()
{
	QNetworkRequest req{QUrl(httpUrl(QStringLiteral("/api/pair/request")))};
	req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
	req.setRawHeader("Accept", "application/json");
	req.setTransferTimeout(std::chrono::milliseconds(kHttpTimeoutMs));
	std::string body = bdsm::build_pair_request_body(store_->client_id(), clientName_.toStdString());
	QNetworkReply *reply = nam_->post(req, QByteArray::fromStdString(body));
	int gen = pairGen_;
	connect(reply, &QNetworkReply::finished, this, [this, reply, gen] { finishReply(reply, gen, true); });
}

void LinkConnection::schedulePoll()
{
	pollTimer_.start((int)(bdsm::kPairPollIntervalS * 1000));
}

void LinkConnection::pollPairStatus()
{
	if (stopped_ || !pairing_ || requestId_.isEmpty())
		return;
	QNetworkRequest req{QUrl(httpUrl(QStringLiteral("/api/pair/status/") + QString::fromUtf8(QUrl::toPercentEncoding(requestId_))))};
	req.setRawHeader("Accept", "application/json");
	req.setTransferTimeout(std::chrono::milliseconds(kHttpTimeoutMs));
	QNetworkReply *reply = nam_->get(req);
	int gen = pairGen_;
	connect(reply, &QNetworkReply::finished, this, [this, reply, gen] { finishReply(reply, gen, false); });
}

void LinkConnection::finishReply(QNetworkReply *reply, int gen, bool isRequest)
{
	reply->deleteLater();
	if (stopped_ || gen != pairGen_ || !pairing_)
		return;
	int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
	QByteArray body = reply->read(65536);
	if (status == 0) { // sem resposta HTTP: erro de rede / timeout
		failPair(QStringLiteral("Sem conexao com %1:%2 (%3)")
				 .arg(QString::fromStdString(ep_.host))
				 .arg(ep_.port)
				 .arg(reply->errorString()));
		return;
	}
	bdsm::PairError err;
	if (isRequest) {
		bdsm::PairRequestInfo info;
		if (!bdsm::parse_pair_request_response(status, body.toStdString(), info, err)) {
			if (err.kind == bdsm::PairErrorKind::RateLimited && rateAttempt_ < bdsm::kMaxRateLimitRetries) {
				double d = bdsm::rate_limit_delay(rateAttempt_++);
				setStatus(ConnStatus::Pending,
					  QStringLiteral("Celular ocupado (HTTP 429); nova tentativa em %1 s").arg(d, 0, 'f', 0));
				rateTimer_.start((int)(d * 1000));
				return;
			}
			failPair(QString::fromStdString(err.message));
			return;
		}
		requestId_ = QString::fromStdString(info.request_id);
		pairDeadlineSec_ = info.expires_in_sec + (int)bdsm::kPairExtraWaitS;
		pairClock_.start();
		QString code = QString::fromStdString(info.code);
		emit pairCodeReceived(code);
		setStatus(ConnStatus::Pending, code);
		schedulePoll();
		return;
	}
	bdsm::PairOutcome out;
	if (!bdsm::parse_pair_status_response(status, body.toStdString(), out, err)) {
		failPair(QString::fromStdString(err.message));
		return;
	}
	onPairOutcome(out);
}

void LinkConnection::onPairOutcome(const bdsm::PairOutcome &out)
{
	if (out.state == "PENDING") {
		if (pairClock_.isValid() && pairClock_.elapsed() > (qint64)pairDeadlineSec_ * 1000) {
			failPair(QStringLiteral("Pedido expirou (90 s) sem aprovacao"));
			return;
		}
		schedulePoll();
		return;
	}
	pairing_ = false;
	pollTimer_.stop();
	if (out.state == "APPROVED" && out.token) {
		store_->set_token(ep_.key(), *out.token); // protegido (DPAPI); nunca logado
		backoff_.reset();
		emit logMessage(QStringLiteral("Pareado com %1").arg(QString::fromStdString(ep_.key())));
		connectWs();
	} else if (out.state == "APPROVED") {
		setStatus(ConnStatus::Error,
			  QStringLiteral("Aprovado, mas o token nao foi entregue (ja consumido). Pareie de novo."));
	} else if (out.state == "DENIED") {
		setStatus(ConnStatus::Error, QStringLiteral("Pedido recusado no celular"));
	} else {
		setStatus(ConnStatus::Error, QStringLiteral("Pedido expirou (90 s) sem aprovacao"));
	}
}

void LinkConnection::failPair(const QString &message)
{
	pairing_ = false;
	pollTimer_.stop();
	rateTimer_.stop();
	setStatus(ConnStatus::Error, message);
	emit logMessage(QStringLiteral("Pareamento com %1 falhou: %2").arg(QString::fromStdString(ep_.key()), message));
}

// ------------------------------------------------------------------ websocket
void LinkConnection::connectWs()
{
	if (stopped_ || sock_ || pairing_)
		return;
	if (!store_->has_token(ep_.key())) {
		setStatus(ConnStatus::Unpaired);
		return;
	}
	setStatus(ConnStatus::Connecting);
	sock_ = new QTcpSocket(this);
	sock_->setProxy(QNetworkProxy::NoProxy);
	wsKey_ = bdsm::ws_generate_key();
	hsBuf_.clear();
	wsReady_ = false;
	rx_ = bdsm::WsReceiver();

	connect(sock_, &QTcpSocket::connected, this, [this] {
		auto token = store_->get_token(ep_.key());
		if (!token) {
			scheduleReconnect(QStringLiteral("token ilegivel (outro usuario/PC?)"));
			return;
		}
		// O token vai so no handshake (?token=); a requisicao nunca e logada.
		std::string req = bdsm::build_handshake_request(ep_.host, ep_.port,
								"/ws/link?token=" + bdsm::url_encode(*token), wsKey_);
		sock_->write(req.data(), (qint64)req.size());
	});
	connect(sock_, &QTcpSocket::readyRead, this, [this] { onSocketData(); });
	connect(sock_, &QAbstractSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) {
		if (sock_)
			scheduleReconnect(sock_->errorString());
	});
	connect(sock_, &QTcpSocket::disconnected, this, [this] { scheduleReconnect(QStringLiteral("conexao encerrada")); });

	hsTimer_.start();
	sock_->connectToHost(QString::fromStdString(ep_.host), (quint16)ep_.port);
}

void LinkConnection::onSocketData()
{
	if (!sock_)
		return;
	QByteArray d = sock_->readAll();
	if (!wsReady_) {
		hsBuf_.append(d.constData(), (size_t)d.size());
		onHandshakeData();
	} else {
		processIncoming(std::string(d.constData(), (size_t)d.size()));
	}
}

void LinkConnection::onHandshakeData()
{
	bdsm::HandshakeResult r = bdsm::parse_handshake_response(hsBuf_, wsKey_);
	if (r.state == bdsm::HandshakeState::NeedMore)
		return;
	if (r.state == bdsm::HandshakeState::Failed) {
		if (r.http_status == 401)
			onUnauthorized(QStringLiteral("HTTP 401"));
		else
			scheduleReconnect(QString::fromStdString(r.error));
		return;
	}
	hsTimer_.stop();
	wsReady_ = true;
	backoff_.reset();
	sentTally_.clear(); // (re)conectou: reenviar o tally atual
	lastRx_.start();
	idleTimer_.start();
	setStatus(ConnStatus::Connected);
	std::string rest = hsBuf_.substr(r.consumed);
	hsBuf_.clear();
	if (!rest.empty())
		processIncoming(rest);
	flushTally();
}

void LinkConnection::processIncoming(const std::string &bytes)
{
	try {
		rx_.feed(bytes);
	} catch (const bdsm::WsError &e) {
		if (!rx_.outgoing.empty()) { // close 1002
			sendRaw(rx_.outgoing);
			rx_.outgoing.clear();
		}
		scheduleReconnect(QString::fromUtf8(e.what()));
		return;
	}
	if (!rx_.outgoing.empty()) { // pong / eco de close
		sendRaw(rx_.outgoing);
		rx_.outgoing.clear();
	}
	std::vector<std::string> msgs;
	msgs.swap(rx_.messages);
	for (const auto &m : msgs) {
		if (!sock_ || stopped_)
			return; // um slot pode ter derrubado a conexao (forget/stop)
		lastRx_.restart();
		bdsm::LinkState st;
		if (bdsm::LinkState::from_json(m, st))
			emit stateReceived(st); // mensagem estranha: ignora
	}
	if (rx_.closed && sock_) {
		if (rx_.has_close_code && rx_.close_code == 1008) // "Access revoked"
			onUnauthorized(QStringLiteral("acesso revogado (1008)"));
		else
			scheduleReconnect(QStringLiteral("conexao fechada pelo celular"));
	}
}

bool LinkConnection::sendRaw(const std::string &data)
{
	if (!sock_ || sock_->state() != QAbstractSocket::ConnectedState)
		return false;
	sock_->write(data.data(), (qint64)data.size());
	return true;
}

void LinkConnection::flushTally()
{
	if (!wsReady_ || desiredTally_.isEmpty() || desiredTally_ == sentTally_)
		return;
	try {
		if (sendRaw(bdsm::encode_text_message(bdsm::tally_update_message(desiredTally_.toStdString()))))
			sentTally_ = desiredTally_;
	} catch (const std::exception &) {
		// mensagem > 8 KB: impossivel aqui (e pequena); ignora
	}
}

void LinkConnection::dropSocket()
{
	hsTimer_.stop();
	idleTimer_.stop();
	bool wasReady = wsReady_;
	wsReady_ = false;
	if (!sock_)
		return;
	QTcpSocket *s = sock_;
	sock_ = nullptr;
	s->disconnect(this); // nenhum sinal chega mais (evita reconexao em cascata)
	if (wasReady && s->state() == QAbstractSocket::ConnectedState) {
		try {
			std::string close = bdsm::encode_close(1000);
			s->write(close.data(), (qint64)close.size());
		} catch (...) {
		}
		s->disconnectFromHost();
	} else {
		s->abort();
	}
	s->deleteLater();
}

void LinkConnection::scheduleReconnect(const QString &why)
{
	if (stopped_)
		return;
	dropSocket();
	double d = backoff_.next();
	setStatus(ConnStatus::Disconnected,
		  QStringLiteral("%1; nova tentativa em %2 s").arg(why).arg(d, 0, 'f', 0));
	reconnectTimer_.start((int)(d * 1000));
}

void LinkConnection::onUnauthorized(const QString &why)
{
	dropSocket();
	store_->clear_token(ep_.key());
	emit tokenInvalid();
	emit logMessage(QStringLiteral("Token recusado (%1) em %2: token apagado").arg(why, QString::fromStdString(ep_.key())));
	if (autoRepair_ && !stopped_) {
		setStatus(ConnStatus::Unpaired, QStringLiteral("Token recusado; repareando"));
		requestPair();
	} else {
		setStatus(ConnStatus::Unpaired, QStringLiteral("Token recusado (401): clique em 'Parear'"));
	}
}

} // namespace bdsm_qt
