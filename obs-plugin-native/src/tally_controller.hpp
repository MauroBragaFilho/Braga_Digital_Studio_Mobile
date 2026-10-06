// Liga o OBS ao tally: ouve os eventos do obs-frontend-api (cena/preview/Modo
// Estudio/colecao) e recalcula o tally (core/tally) para todos os celulares.
// Os eventos do OBS chegam por callback C possivelmente de outra thread: o
// callback so enfileira um slot na thread da UI (Qt::QueuedConnection).
#pragma once

#include <QObject>
#include <QTimer>
#include <QPointer>

namespace bdsm_qt {

class DeviceManager;

class TallyController : public QObject {
	Q_OBJECT
public:
	explicit TallyController(DeviceManager *mgr, QObject *parent = nullptr);
	~TallyController() override;

	// Registra/remove o callback do obs-frontend-api e liga/desliga o timer de seguranca.
	void attach();
	void detach();

public slots:
	// Agenda um recalculo (coalescido): pode ser chamado de qualquer lugar da UI.
	void requestRecompute();

private slots:
	void recompute();

private:
	QPointer<DeviceManager> mgr_;
	QTimer safety_;   // rede de seguranca: visibilidade de itens nao gera evento do frontend
	QTimer debounce_; // coalesce rajadas de eventos
	bool attached_ = false;
};

} // namespace bdsm_qt
