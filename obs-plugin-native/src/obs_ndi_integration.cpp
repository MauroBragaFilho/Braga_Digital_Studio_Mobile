#include "obs_ndi_integration.hpp"

#include <cstring>

#include <obs-frontend-api.h>
#include <obs-module.h>

#include "obs_scene_graph.hpp" // kNdiSourceId / kNdiSettingName

namespace bdsm_qt {

namespace {

// Nome e liberacao da referencia obtida por get_* do frontend.
std::optional<std::string> take_name(obs_source_t *src)
{
	if (!src)
		return std::nullopt;
	const char *n = obs_source_get_name(src);
	std::optional<std::string> r;
	if (n)
		r = std::string(n);
	obs_source_release(src);
	return r;
}

std::string current_target_scene()
{
	obs_source_t *src = obs_frontend_preview_program_mode_active() ? obs_frontend_get_current_preview_scene()
								       : obs_frontend_get_current_scene();
	return take_name(src).value_or(std::string());
}

struct ScanCtx {
	ObsNdiScan *scan;
};

// Todas as fontes (entradas): guarda o nome e, se for ndi_source, o ndi_source_name.
bool enum_sources_cb(void *param, obs_source_t *src)
{
	auto *ctx = static_cast<ScanCtx *>(param);
	const char *name = obs_source_get_name(src);
	if (!name)
		return true;
	ctx->scan->taken_names.insert(name);
	const char *id = obs_source_get_unversioned_id(src);
	if (id && std::strcmp(id, kNdiSourceId) == 0) {
		bdsm::ObsNdiSourceInfo info;
		info.name = name;
		if (obs_data_t *settings = obs_source_get_settings(src)) { // precisa de release
			const char *n = obs_data_get_string(settings, kNdiSettingName);
			if (n)
				info.ndi_name = n;
			obs_data_release(settings);
		}
		ctx->scan->sources.push_back(std::move(info));
	}
	return true;
}

// Cenas: nome + em quais cenas cada fonte NDI esta (a busca olha tambem dentro de grupos).
bool enum_scenes_cb(void *param, obs_source_t *scene_src)
{
	auto *ctx = static_cast<ScanCtx *>(param);
	const char *sname = obs_source_get_name(scene_src);
	if (!sname)
		return true;
	ctx->scan->taken_names.insert(sname);
	obs_scene_t *scene = obs_scene_from_source(scene_src); // emprestado
	if (!scene)
		return true;
	for (auto &info : ctx->scan->sources)
		if (obs_scene_find_source_recursive(scene, info.name.c_str())) // item emprestado, sem release
			info.scenes.push_back(sname);
	return true;
}

} // namespace

bool distroav_available()
{
	const char *id = nullptr;
	for (size_t i = 0; obs_enum_source_types(i, &id); ++i) {
		if (id && std::strcmp(id, kNdiSourceId) == 0)
			return obs_source_get_display_name(id) != nullptr; // registrado e utilizavel
	}
	return false;
}

ObsNdiScan scan_ndi()
{
	ObsNdiScan scan;
	scan.distroav = distroav_available();
	scan.target_scene = current_target_scene();
	ScanCtx ctx{&scan};
	obs_enum_sources(enum_sources_cb, &ctx); // inputs (as cenas nao entram aqui)
	obs_enum_scenes(enum_scenes_cb, &ctx);
	return scan;
}

AddOutcome add_ndi_source_for(const std::string &device_name, const std::optional<std::string> &ndi_stream_name)
{
	AddOutcome out;
	ObsNdiScan scan = scan_ndi();
	out.target_scene = scan.target_scene;
	if (!scan.distroav) {
		out.error_key = "BdsmLink.Err.NoDistroAV";
		return out;
	}
	bdsm::AddPlan plan =
		bdsm::plan_add_ndi_source(device_name, ndi_stream_name, scan.target_scene, scan.sources, scan.taken_names);
	out.action = plan.action;
	out.source_name = plan.source_name;
	if (plan.action == bdsm::AddAction::NoNdiName) {
		out.error_key = "BdsmLink.Err.NdiOff";
		return out;
	}
	if (plan.action == bdsm::AddAction::AlreadyInScene) {
		out.ok = true; // nada a fazer: nao duplica
		return out;
	}

	obs_source_t *scene_src = obs_frontend_preview_program_mode_active() ? obs_frontend_get_current_preview_scene()
									     : obs_frontend_get_current_scene();
	obs_scene_t *scene = scene_src ? obs_scene_from_source(scene_src) : nullptr; // emprestado de scene_src
	if (!scene) {
		if (scene_src)
			obs_source_release(scene_src);
		out.error_key = "BdsmLink.Err.NoScene";
		return out;
	}

	obs_source_t *ndi = nullptr;
	if (plan.action == bdsm::AddAction::AddExisting) {
		ndi = obs_get_source_by_name(plan.source_name.c_str()); // referencia nova
	} else { // CreateNew
		obs_data_t *settings = obs_data_create();
		obs_data_set_string(settings, kNdiSettingName, plan.ndi_name.c_str());
		ndi = obs_source_create(kNdiSourceId, plan.source_name.c_str(), settings, nullptr); // referencia nova
		obs_data_release(settings);
	}
	if (!ndi) {
		obs_source_release(scene_src);
		out.error_key = "BdsmLink.Err.CreateFailed";
		return out;
	}
	// A cena guarda a propria referencia ao item/fonte; a nossa e liberada em seguida.
	obs_sceneitem_t *item = obs_scene_add(scene, ndi); // item emprestado (nao precisa de release)
	obs_source_release(ndi);
	obs_source_release(scene_src);
	if (!item) {
		out.error_key = "BdsmLink.Err.CreateFailed";
		return out;
	}
	out.ok = true;
	return out;
}

} // namespace bdsm_qt
