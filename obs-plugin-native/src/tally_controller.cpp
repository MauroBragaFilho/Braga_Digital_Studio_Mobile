#include "tally_controller.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>

#include "device_manager.hpp"
#include "obs_scene_graph.hpp"
#include "plugin-support.h"

namespace bdsm_qt {

namespace {
constexpr int kSafetyMs = 1000;  // recalcula a cada ~1 s mesmo sem evento (item ligado/desligado)
constexpr int kTransitionPollMs = 120; // reavaliacao durante uma transicao
constexpr int kDebounceMs = 30;  // junta rajadas de eventos (troca de cena gera varios)

// Assinatura EXATA de obs_frontend_event_cb: void (*)(enum obs_frontend_event, void *)
void frontend_event_cb(enum obs_frontend_event event, void *privateData)
{
	auto *self = static_cast<TallyController *>(privateData);
	if (!self)
		return;
	switch (event) {
	case OBS_FRONTEND_EVENT_SCENE_CHANGED:
	case OBS_FRONTEND_EVENT_PREVIEW_SCENE_CHANGED:
	case OBS_FRONTEND_EVENT_STUDIO_MODE_ENABLED:
	case OBS_FRONTEND_EVENT_STUDIO_MODE_DISABLED:
	case OBS_FRONTEND_EVENT_SCENE_COLLECTION_CHANGED:
	case OBS_FRONTEND_EVENT_SCENE_LIST_CHANGED:
	case OBS_FRONTEND_EVENT_TRANSITION_STOPPED:
	case OBS_FRONTEND_EVENT_FINISHED_LOADING:
		// Pode vir de outra thread: so enfileira; o calculo roda na thread da UI.
		QMetaObject::invokeMethod(self, "requestRecompute", Qt::QueuedConnection);
		break;
	default:
		break;
	}
}
} // namespace

TallyController::TallyController(DeviceManager *mgr, QObject *parent) : QObject(parent), mgr_(mgr)
{
	safety_.setInterval(kSafetyMs);
	debounce_.setSingleShot(true);
	debounce_.setInterval(kDebounceMs);
	connect(&safety_, &QTimer::timeout, this, &TallyController::recompute);
	connect(&debounce_, &QTimer::timeout, this, &TallyController::recompute);
	if (mgr)
		connect(mgr, &DeviceManager::tallyInputsChanged, this, &TallyController::requestRecompute);
}

TallyController::~TallyController() { detach(); }

void TallyController::attach()
{
	if (attached_)
		return;
	attached_ = true;
	obs_frontend_add_event_callback(frontend_event_cb, this);
	safety_.start();
	requestRecompute();
}

void TallyController::detach()
{
	if (!attached_)
		return;
	attached_ = false;
	obs_frontend_remove_event_callback(frontend_event_cb, this);
	safety_.stop();
	debounce_.stop();
}

void TallyController::requestRecompute()
{
	if (attached_ && !debounce_.isActive())
		debounce_.start();
}

void TallyController::recompute()
{
	if (!attached_ || !mgr_)
		return;
	try {
		ObsSceneGraph graph;
		mgr_->applyTally(bdsm::compute_tally(graph, mgr_->tallyTargets()));
		// Durante uma transicao nao ha evento de "iniciou": reavalia rapido ate ela terminar
		// (o fim tambem gera TRANSITION_STOPPED).
		if (graph.transition_active())
			QTimer::singleShot(kTransitionPollMs, this, &TallyController::requestRecompute);
	} catch (const std::exception &e) { // nunca derrubar o OBS
		obs_log(LOG_WARNING, "Falha ao calcular o tally: %s", e.what());
	}
}

} // namespace bdsm_qt
