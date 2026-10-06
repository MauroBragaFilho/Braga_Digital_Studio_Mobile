/*
BDSM Link - dock do OBS Studio para celulares BDSM (Braga Digital Studio Mobile)
Copyright (C) 2026 Braga Digital Studio

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along
with this program. If not, see <https://www.gnu.org/licenses/>
*/

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>

#include <QHostInfo>
#include <QStandardPaths>
#include <memory>
#include <string>

#include "bdsm_dock.hpp"
#include "device_manager.hpp"
#include "tally_controller.hpp"

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE(PLUGIN_NAME, "en-US")
OBS_MODULE_AUTHOR("Braga Digital Studio")

MODULE_EXPORT const char *obs_module_name(void)
{
	return "BDSM Link";
}

MODULE_EXPORT const char *obs_module_description(void)
{
	return obs_module_text("BdsmLink.Description");
}

namespace {

constexpr const char *kDockId = "bdsm-link-dock";

struct Context {
	std::unique_ptr<bdsm_qt::DeviceManager> mgr;
	std::unique_ptr<bdsm_qt::TallyController> tally;
	bool exitCallbackAdded = false;
};

Context *g_ctx = nullptr;

// Assinatura exata de obs_frontend_event_cb.
void on_frontend_event(enum obs_frontend_event event, void *)
{
	if (event != OBS_FRONTEND_EVENT_EXIT || !g_ctx)
		return;
	// OBS esta encerrando: para o tally e fecha os sockets antes de a janela principal cair.
	if (g_ctx->tally)
		g_ctx->tally->detach();
	if (g_ctx->mgr)
		g_ctx->mgr->shutdown();
}

std::string config_dir()
{
	std::string dir;
	char *p = obs_module_config_path(""); // pode ser NULL; liberar com bfree
	if (p) {
		dir = p;
		bfree(p);
	}
	if (dir.empty())
		dir = (QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + "/bdsm-link").toStdString();
	return dir;
}

} // namespace

bool obs_module_load(void)
{
	// Nada pesado aqui: o dock so pode ser criado depois que a janela principal existe
	// (obs_module_post_load).
	obs_log(LOG_INFO, "plugin carregado (versao %s)", PLUGIN_VERSION);
	return true;
}

void obs_module_post_load(void)
{
	if (g_ctx)
		return;
	g_ctx = new Context();
	try {
		g_ctx->mgr.reset(new bdsm_qt::DeviceManager(config_dir(), QHostInfo::localHostName()));
		g_ctx->tally.reset(new bdsm_qt::TallyController(g_ctx->mgr.get()));

		// Logs: o token NUNCA passa por aqui (so mensagens de estado).
		QObject::connect(g_ctx->mgr.get(), &bdsm_qt::DeviceManager::logMessage, g_ctx->mgr.get(),
				 [](const QString &m) { obs_log(LOG_INFO, "%s", m.toUtf8().constData()); });

		auto *dock = new bdsm_qt::BdsmDock(g_ctx->mgr.get());
		// O OBS assume a posse do widget (envolve em um QDockWidget, com layout salvo por id).
		if (!obs_frontend_add_dock_by_id(kDockId, obs_module_text("BdsmLink.Title"), dock)) {
			obs_log(LOG_ERROR, "nao foi possivel registrar o dock '%s' (id ja existe?)", kDockId);
			delete dock;
		}

		obs_frontend_add_event_callback(on_frontend_event, nullptr);
		g_ctx->exitCallbackAdded = true;

		g_ctx->mgr->load();
		g_ctx->tally->attach();
		obs_log(LOG_INFO, "dock registrado (View > Docks > %s)", obs_module_text("BdsmLink.Title"));
	} catch (const std::exception &e) {
		obs_log(LOG_ERROR, "falha ao iniciar o BDSM Link: %s", e.what());
	}
}

void obs_module_unload(void)
{
	if (!g_ctx)
		return;
	if (g_ctx->exitCallbackAdded)
		obs_frontend_remove_event_callback(on_frontend_event, nullptr);
	if (g_ctx->tally)
		g_ctx->tally->detach();
	// O dock (widget) pertence ao OBS e e destruido com a janela principal; ele guarda
	// QPointer para o gerenciador, que vira nulo quando o apagamos aqui.
	g_ctx->tally.reset();
	g_ctx->mgr.reset();
	delete g_ctx;
	g_ctx = nullptr;
	obs_log(LOG_INFO, "plugin descarregado");
}
