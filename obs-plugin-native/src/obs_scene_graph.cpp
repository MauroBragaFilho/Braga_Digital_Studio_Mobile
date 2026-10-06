#include "obs_scene_graph.hpp"

#include <algorithm>
#include <cstring>

#include <obs-frontend-api.h>
#include <obs-module.h>

namespace bdsm_qt {

namespace {

// Nome da fonte e liberacao da referencia obtida por get_* (frontend / get_source_by_name).
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

struct EnumCtx {
	std::vector<std::string> *out;
	int depth;
};

// bool (*)(obs_scene_t *, obs_sceneitem_t *, void *): true = continuar
bool enum_item_cb(obs_scene_t *, obs_sceneitem_t *item, void *param)
{
	auto *ctx = static_cast<EnumCtx *>(param);
	if (!obs_sceneitem_visible(item))
		return true;
	if (obs_sceneitem_is_group(item)) {
		obs_scene_t *group_scene = obs_sceneitem_group_get_scene(item); // emprestado
		if (group_scene && ctx->depth < 8) {
			EnumCtx sub{ctx->out, ctx->depth + 1};
			obs_scene_enum_items(group_scene, enum_item_cb, &sub);
		}
		return true;
	}
	obs_source_t *src = obs_sceneitem_get_source(item); // emprestado
	const char *name = src ? obs_source_get_name(src) : nullptr;
	if (name)
		ctx->out->push_back(name);
	return true;
}

bool enum_ndi_cb(void *param, obs_source_t *src)
{
	auto *out = static_cast<std::vector<std::string> *>(param);
	const char *id = obs_source_get_unversioned_id(src);
	if (id && std::strcmp(id, kNdiSourceId) == 0) {
		const char *name = obs_source_get_name(src);
		if (name && *name)
			out->push_back(name);
	}
	return true;
}

} // namespace

std::optional<std::string> ObsSceneGraph::program_scene()
{
	return take_name(obs_frontend_get_current_scene());
}

std::optional<std::string> ObsSceneGraph::preview_scene()
{
	if (!obs_frontend_preview_program_mode_active())
		return std::nullopt; // fora do Modo Estudio nao ha preview
	return take_name(obs_frontend_get_current_preview_scene());
}

bool ObsSceneGraph::is_scene(const std::string &source)
{
	obs_source_t *src = obs_get_source_by_name(source.c_str());
	if (!src)
		return false;
	bool r = obs_scene_from_source(src) != nullptr;
	obs_source_release(src);
	return r;
}

std::vector<std::string> ObsSceneGraph::scene_sources(const std::string &scene)
{
	std::vector<std::string> out;
	obs_source_t *src = obs_get_source_by_name(scene.c_str());
	if (!src)
		return out;
	obs_scene_t *sc = obs_scene_from_source(src); // emprestado: vive enquanto `src` viver
	if (sc) {
		EnumCtx ctx{&out, 0};
		obs_scene_enum_items(sc, enum_item_cb, &ctx);
	}
	obs_source_release(src);
	return out;
}

std::optional<std::string> ObsSceneGraph::ndi_name_of(const std::string &source)
{
	obs_source_t *src = obs_get_source_by_name(source.c_str());
	if (!src)
		return std::nullopt;
	std::optional<std::string> r;
	const char *id = obs_source_get_unversioned_id(src);
	if (id && std::strcmp(id, kNdiSourceId) == 0) {
		obs_data_t *settings = obs_source_get_settings(src); // precisa de release
		if (settings) {
			const char *n = obs_data_get_string(settings, kNdiSettingName);
			if (n && *n)
				r = std::string(n);
			obs_data_release(settings);
		}
	}
	obs_source_release(src);
	return r;
}

bool ObsSceneGraph::transition_active()
{
	obs_source_t *tr = obs_get_output_source(0); // referencia nova; o canal 0 e a transicao
	if (!tr)
		return false;
	bool active = obs_source_get_type(tr) == OBS_SOURCE_TYPE_TRANSITION && obs_transition_is_active(tr);
	obs_source_release(tr);
	return active;
}

std::optional<std::string> ObsSceneGraph::transition_from_scene()
{
	obs_source_t *tr = obs_get_output_source(0);
	if (!tr)
		return std::nullopt;
	std::optional<std::string> r;
	if (obs_source_get_type(tr) == OBS_SOURCE_TYPE_TRANSITION && obs_transition_is_active(tr))
		r = take_name(obs_transition_get_source(tr, OBS_TRANSITION_SOURCE_A)); // libera a referencia do lado A
	obs_source_release(tr);
	return r;
}

std::vector<std::string> list_ndi_sources()
{
	std::vector<std::string> names;
	obs_enum_sources(enum_ndi_cb, &names);
	std::sort(names.begin(), names.end());
	names.erase(std::unique(names.begin(), names.end()), names.end());
	return names;
}

} // namespace bdsm_qt
