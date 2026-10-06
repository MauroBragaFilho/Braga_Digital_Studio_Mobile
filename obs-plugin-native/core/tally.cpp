#include "tally.hpp"

#include <set>

namespace bdsm {
namespace {

bool is_ascii_space(unsigned char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\v' || c == '\f'; }

// Decodifica um ponto de codigo UTF-8 em s[i]; devolve o comprimento (>=1). Invalido -> 1 byte.
size_t decode_cp(const std::string &s, size_t i, unsigned &cp)
{
	unsigned char c = (unsigned char)s[i];
	size_t n = 1;
	if (c < 0x80) {
		cp = c;
		return 1;
	} else if ((c & 0xE0) == 0xC0) {
		n = 2;
		cp = c & 0x1F;
	} else if ((c & 0xF0) == 0xE0) {
		n = 3;
		cp = c & 0x0F;
	} else if ((c & 0xF8) == 0xF0) {
		n = 4;
		cp = c & 0x07;
	} else {
		cp = 0xFFFD;
		return 1;
	}
	if (i + n > s.size()) {
		cp = 0xFFFD;
		return 1;
	}
	for (size_t k = 1; k < n; ++k) {
		unsigned char cc = (unsigned char)s[i + k];
		if ((cc & 0xC0) != 0x80) {
			cp = 0xFFFD;
			return 1;
		}
		cp = (cp << 6) | (cc & 0x3F);
	}
	return n;
}

void append_cp(std::string &o, unsigned cp)
{
	if (cp < 0x80) {
		o += (char)cp;
	} else if (cp < 0x800) {
		o += (char)(0xC0 | (cp >> 6));
		o += (char)(0x80 | (cp & 0x3F));
	} else if (cp < 0x10000) {
		o += (char)(0xE0 | (cp >> 12));
		o += (char)(0x80 | ((cp >> 6) & 0x3F));
		o += (char)(0x80 | (cp & 0x3F));
	} else {
		o += (char)(0xF0 | (cp >> 18));
		o += (char)(0x80 | ((cp >> 12) & 0x3F));
		o += (char)(0x80 | ((cp >> 6) & 0x3F));
		o += (char)(0x80 | (cp & 0x3F));
	}
}

unsigned fold_cp(unsigned cp)
{
	if (cp >= 'A' && cp <= 'Z')
		return cp + 32;
	if (cp >= 0xC0 && cp <= 0xDE && cp != 0xD7)
		return cp + 32;
	if ((cp >= 0x100 && cp <= 0x137) || (cp >= 0x14A && cp <= 0x177))
		return (cp % 2 == 0) ? cp + 1 : cp;
	return cp;
}

} // namespace

std::string norm(const std::string &s)
{
	std::string out;
	bool pending_space = false;
	size_t i = 0;
	while (i < s.size()) {
		unsigned cp;
		size_t n = decode_cp(s, i, cp);
		i += n;
		bool space = (cp < 0x80 && is_ascii_space((unsigned char)cp)) || cp == 0xA0;
		if (space) {
			pending_space = !out.empty();
			continue;
		}
		if (pending_space) {
			out += ' ';
			pending_space = false;
		}
		append_cp(out, fold_cp(cp));
	}
	return out; // espacos finais nunca sao emitidos (pending_space so vira ' ' antes de outro caractere)
}

std::optional<std::string> expected_ndi_name(const std::optional<std::string> &ndi_stream_name)
{
	if (!ndi_stream_name || ndi_stream_name->empty())
		return std::nullopt;
	return "BDSM (" + *ndi_stream_name + ")";
}

namespace {

struct Reach {
	std::set<std::string> ndi;   // nomes NDI normalizados
	std::set<std::string> names; // nomes de fonte normalizados
};

void walk(SceneGraph &g, const std::string &scene, int depth, std::set<std::string> &visited, Reach &r)
{
	if (visited.count(scene) || depth > 16) // protege contra ciclos
		return;
	visited.insert(scene);
	for (const std::string &src : g.scene_sources(scene)) {
		r.names.insert(norm(src));
		if (g.is_scene(src)) {
			walk(g, src, depth + 1, visited, r);
			continue;
		}
		if (auto n = g.ndi_name_of(src))
			if (!n->empty())
				r.ndi.insert(norm(*n));
	}
}

Reach collect(SceneGraph &g, const std::optional<std::string> &scene)
{
	Reach r;
	if (!scene)
		return r;
	std::set<std::string> visited;
	walk(g, *scene, 0, visited, r);
	return r;
}

bool ends_with(const std::string &s, const std::string &suf)
{
	return s.size() >= suf.size() && s.compare(s.size() - suf.size(), suf.size(), suf) == 0;
}

} // namespace

bool ndi_name_matches_stream(const std::string &obs_ndi_name, const std::string &ndi_stream_name)
{
	if (ndi_stream_name.empty())
		return false;
	std::string n = norm(obs_ndi_name);
	if (n == norm("BDSM (" + ndi_stream_name + ")"))
		return true;
	// tolerancia: a parte "MAQUINA" pode diferir; compara o "(sender)" final
	std::string suffix = "(" + norm(ndi_stream_name) + ")";
	return ends_with(n, suffix);
}

namespace {

bool matches(const TallyTarget &t, const Reach &r)
{
	if (!t.mapping.empty())
		return r.names.count(norm(t.mapping)) > 0;
	if (!t.ndi_stream_name || t.ndi_stream_name->empty())
		return false;
	for (const auto &n : r.ndi)
		if (ndi_name_matches_stream(n, *t.ndi_stream_name))
			return true;
	return false;
}

} // namespace

std::map<std::string, std::string> compute_tally(SceneGraph &graph, const std::vector<TallyTarget> &targets)
{
	Reach prog = collect(graph, graph.program_scene());
	Reach prev = collect(graph, graph.preview_scene());
	if (graph.transition_active()) {
		auto from = graph.transition_from_scene();
		if (from && graph.is_scene(*from)) {
			// `prog` (destino) ainda nao esta totalmente no ar: vira PREVIEW; a origem segue PROGRAM.
			Reach incoming = prog;
			prog = collect(graph, from);
			prev.ndi.insert(incoming.ndi.begin(), incoming.ndi.end());
			prev.names.insert(incoming.names.begin(), incoming.names.end());
		}
	}
	std::map<std::string, std::string> out;
	for (const auto &t : targets) {
		if (matches(t, prog))
			out[t.key] = "PROGRAM";
		else if (matches(t, prev))
			out[t.key] = "PREVIEW";
		else
			out[t.key] = "OFF";
	}
	return out;
}

bool BatteryAlert::update(int level)
{
	if (!alerted_ && level < low_) {
		alerted_ = true;
		return true;
	}
	if (alerted_ && level >= rearm_)
		alerted_ = false;
	return false;
}

} // namespace bdsm
