#include "ndi_link.hpp"

#include <algorithm>

#include "tally.hpp"

namespace bdsm {

std::string friendly_source_name(const std::string &device_name)
{
	std::string clean;
	for (unsigned char c : device_name)
		if (c >= 0x20 && c != 0x7F) // descarta controles (bytes UTF-8 >= 0x80 passam)
			clean += (char)c;
	// apara espacos nas pontas
	size_t b = clean.find_first_not_of(' ');
	size_t e = clean.find_last_not_of(' ');
	clean = (b == std::string::npos) ? std::string() : clean.substr(b, e - b + 1);
	if (clean.empty())
		clean = "Celular";
	// limita a 64 bytes sem cortar no meio de um caractere UTF-8
	if (clean.size() > 64) {
		size_t cut = 64;
		while (cut > 0 && ((unsigned char)clean[cut] & 0xC0) == 0x80)
			--cut;
		clean.resize(cut);
	}
	return "BDSM - " + clean;
}

std::string unique_source_name(const std::string &base, const std::set<std::string> &taken)
{
	if (!taken.count(base))
		return base;
	for (int i = 2; i < 10000; ++i) {
		std::string cand = base + " (" + std::to_string(i) + ")";
		if (!taken.count(cand))
			return cand;
	}
	return base + " (x)"; // inalcancavel na pratica
}

AddPlan plan_add_ndi_source(const std::string &device_name, const std::optional<std::string> &ndi_stream_name,
			    const std::string &target_scene, const std::vector<ObsNdiSourceInfo> &ndi_sources,
			    const std::set<std::string> &taken_names)
{
	AddPlan plan;
	auto expected = expected_ndi_name(ndi_stream_name);
	if (!expected) {
		plan.action = AddAction::NoNdiName;
		return plan;
	}
	plan.ndi_name = *expected;

	const ObsNdiSourceInfo *best = nullptr;
	auto score = [&](const ObsNdiSourceInfo &s) {
		int sc = 0;
		if (norm(s.ndi_name) == norm(*expected))
			sc += 4;
		if (std::find(s.scenes.begin(), s.scenes.end(), target_scene) != s.scenes.end())
			sc += 2;
		if (!s.scenes.empty())
			sc += 1;
		return sc;
	};
	int best_score = -1;
	for (const auto &s : ndi_sources) {
		if (!ndi_name_matches_stream(s.ndi_name, *ndi_stream_name))
			continue;
		int sc = score(s);
		if (sc > best_score || (sc == best_score && s.name < best->name)) {
			best = &s;
			best_score = sc;
		}
	}

	if (best) {
		plan.source_name = best->name;
		plan.in_scenes = best->scenes;
		bool in_target = std::find(best->scenes.begin(), best->scenes.end(), target_scene) != best->scenes.end();
		plan.action = in_target ? AddAction::AlreadyInScene : AddAction::AddExisting;
		return plan;
	}
	plan.action = AddAction::CreateNew;
	plan.source_name = unique_source_name(friendly_source_name(device_name), taken_names);
	return plan;
}

} // namespace bdsm
