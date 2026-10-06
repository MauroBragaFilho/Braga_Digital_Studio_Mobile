#include "link_state.hpp"

#include <cerrno>
#include <cmath>
#include <cstdlib>

#include "json.hpp"

namespace bdsm {
namespace {

int as_int(const Json *v, int def)
{
	if (!v)
		return def;
	if (v->is_number()) {
		if (!std::isfinite(v->number) || std::fabs(v->number) > 2e9)
			return def;
		return (int)std::trunc(v->number);
	}
	if (v->is_string()) { // int("88") funciona no Python; "oops" volta ao padrao
		const std::string &s = v->str;
		size_t b = s.find_first_not_of(" \t\r\n");
		size_t e = s.find_last_not_of(" \t\r\n");
		if (b == std::string::npos)
			return def;
		std::string t = s.substr(b, e - b + 1);
		char *end = nullptr;
		errno = 0;
		long n = std::strtol(t.c_str(), &end, 10);
		if (errno != 0 || end == t.c_str() || *end != '\0' || n > 2000000000L || n < -2000000000L)
			return def;
		return (int)n;
	}
	return def; // bool, null, array, objeto
}

bool as_bool(const Json *v, bool def) { return (v && v->is_bool()) ? v->boolean : def; }

std::string as_str(const Json *v, const std::string &def) { return (v && v->is_string()) ? v->str : def; }

std::string upper_ascii(std::string s)
{
	for (auto &c : s)
		if (c >= 'a' && c <= 'z')
			c = (char)(c - 'a' + 'A');
	return s;
}

bool has_non_space(const std::string &s) { return s.find_first_not_of(" \t\r\n\f\v") != std::string::npos; }

} // namespace

std::optional<std::string> LinkState::ndi_source_name() const
{
	if (!ndi_stream_name)
		return std::nullopt;
	return "BDSM (" + *ndi_stream_name + ")";
}

bool LinkState::from_json(const std::string &text, LinkState &out, std::string *error)
{
	Json j;
	std::string perr;
	if (!parse_json(text, j, &perr)) {
		if (error)
			*error = perr;
		return false;
	}
	if (!j.is_object()) {
		if (error)
			*error = "LinkState nao e um objeto JSON";
		return false;
	}
	LinkState s;
	s.device_name = as_str(j.find("deviceName"), "BDSM Device");
	int bat = as_int(j.find("batteryLevel"), 0);
	s.battery_level = bat < 0 ? 0 : (bat > 100 ? 100 : bat);
	s.is_charging = as_bool(j.find("isCharging"), false);
	s.capture_source = as_str(j.find("captureSource"), "--");
	s.camera_lens = as_str(j.find("cameraLens"), "--");
	s.fps = as_int(j.find("fps"), 0);
	s.microphone = as_str(j.find("microphone"), "--");
	s.is_recording = as_bool(j.find("isRecording"), false);
	const Json *ndi = j.find("ndiStreamName");
	if (ndi && ndi->is_string() && has_non_space(ndi->str))
		s.ndi_stream_name = ndi->str;
	std::string t = upper_ascii(as_str(j.find("tally"), "OFF"));
	s.tally = (t == "PREVIEW" || t == "PROGRAM" || t == "OFF") ? t : "OFF";
	out = std::move(s);
	return true;
}

std::string tally_update_message(const std::string &state)
{
	const char *s = (state == "PROGRAM" || state == "PREVIEW") ? state.c_str() : "OFF";
	return std::string("{\"type\":\"TALLY_UPDATE\",\"state\":\"") + s + "\"}";
}

} // namespace bdsm
