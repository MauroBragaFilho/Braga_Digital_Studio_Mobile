// Casos de TallyTests / BatteryTests / StateTests / backoff / parse_endpoint de
// obs-plugin/tests/test_unit.py, reproduzidos 1:1.
#include <map>
#include <set>

#include "link_state.hpp"
#include "minitest.hpp"
#include "tally.hpp"
#include "util.hpp"

using namespace bdsm;

namespace {

// scenes: {cena: [fontes]}; grupos ja vem expandidos (como no grafo real);
// ndi: {fonte: ndi_source_name}.
class FakeGraph : public SceneGraph {
public:
	std::map<std::string, std::vector<std::string>> scenes;
	std::map<std::string, std::string> ndi;
	std::optional<std::string> program, preview;
	std::set<std::pair<std::string, std::string>> hidden;
	bool in_transition = false;
	std::optional<std::string> from_scene;

	bool transition_active() override { return in_transition; }
	std::optional<std::string> transition_from_scene() override { return from_scene; }

	std::optional<std::string> program_scene() override { return program; }
	std::optional<std::string> preview_scene() override { return preview; }
	std::vector<std::string> scene_sources(const std::string &scene) override
	{
		std::vector<std::string> out;
		auto it = scenes.find(scene);
		if (it == scenes.end())
			return out;
		for (const auto &s : it->second)
			if (!hidden.count({scene, s}))
				out.push_back(s);
		return out;
	}
	bool is_scene(const std::string &source) override { return scenes.count(source) > 0; }
	std::optional<std::string> ndi_name_of(const std::string &source) override
	{
		auto it = ndi.find(source);
		if (it == ndi.end())
			return std::nullopt;
		return it->second;
	}
};

FakeGraph base_graph()
{
	FakeGraph g;
	g.scenes = {{"Cena A51", {"Cam A51"}}, {"Cena S20", {"Cam S20"}}, {"Texto", {"Logo"}}};
	g.ndi = {{"Cam A51", "BDSM (Galaxy A51)"}, {"Cam S20", "BDSM (Galaxy S20 FE)"}};
	return g;
}

TallyTarget A51() { return TallyTarget{"a51", std::string("Galaxy A51"), ""}; }
TallyTarget S20() { return TallyTarget{"s20", std::string("Galaxy S20 FE"), ""}; }

using Result = std::map<std::string, std::string>;

} // namespace

TEST(tally_simple_scene_program)
{
	auto g = base_graph();
	g.program = "Cena A51";
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "PROGRAM"}, {"s20", "OFF"}}));
}

TEST(tally_studio_mode_preview_and_program)
{
	auto g = base_graph();
	g.program = "Cena A51";
	g.preview = "Cena S20";
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "PROGRAM"}, {"s20", "PREVIEW"}}));
}

TEST(tally_studio_mode_off_means_no_preview)
{
	auto g = base_graph();
	g.program = "Cena A51";
	CHECK_EQ(compute_tally(g, {S20()})["s20"], "OFF");
}

TEST(tally_program_beats_preview)
{
	auto g = base_graph();
	g.program = "Cena A51";
	g.preview = "Cena A51";
	CHECK_EQ(compute_tally(g, {A51()})["a51"], "PROGRAM");
}

TEST(tally_nested_scene_and_cycle)
{
	auto g = base_graph();
	g.program = "Mestre";
	g.scenes["Mestre"] = {"Cena A51", "Logo"};
	g.scenes["Cena A51"] = {"Cam A51", "Mestre"}; // ciclo
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "PROGRAM"}, {"s20", "OFF"}}));
}

TEST(tally_group_expanded_sources)
{
	auto g = base_graph();
	g.program = "Com grupo";
	g.scenes["Com grupo"] = {"Logo", "Cam S20"}; // filho do grupo ja vem plano
	CHECK_EQ(compute_tally(g, {S20()})["s20"], "PROGRAM");
}

TEST(tally_hidden_item_does_not_count)
{
	auto g = base_graph();
	g.program = "Cena A51";
	g.hidden.insert({"Cena A51", "Cam A51"});
	CHECK_EQ(compute_tally(g, {A51()})["a51"], "OFF");
}

TEST(tally_case_and_whitespace_tolerant)
{
	auto g = base_graph();
	g.program = "Cena A51";
	g.ndi["Cam A51"] = "  bdsm  (GALAXY   a51) ";
	CHECK_EQ(compute_tally(g, {A51()})["a51"], "PROGRAM");
	CHECK_EQ(norm("  A  B "), "a b");
}

TEST(tally_machine_prefix_tolerance)
{
	auto g = base_graph();
	g.program = "Cena A51";
	g.ndi["Cam A51"] = "OUTRO-PC (Galaxy A51)";
	CHECK_EQ(compute_tally(g, {A51()})["a51"], "PROGRAM");
}

TEST(tally_ndi_off_never_matches)
{
	auto g = base_graph();
	g.program = "Cena A51";
	CHECK_EQ(compute_tally(g, {TallyTarget{"x", std::nullopt, ""}})["x"], "OFF");
}

TEST(tally_no_false_positive_on_prefix)
{
	auto g = base_graph();
	g.program = "Cena A51";
	g.ndi["Cam A51"] = "BDSM (Galaxy A510)";
	CHECK_EQ(compute_tally(g, {A51()})["a51"], "OFF");
}

TEST(tally_manual_mapping)
{
	auto g = base_graph();
	g.program = "Cena S20";
	g.ndi["Cam S20"] = "QUALQUER (outro nome)";
	TallyTarget autoT{"s20", std::string("Galaxy S20 FE"), ""};
	TallyTarget manual{"s20", std::string("Galaxy S20 FE"), "cam s20"};
	CHECK_EQ(compute_tally(g, {autoT})["s20"], "OFF"); // nome NDI diferente: so o mapeamento resolve
	CHECK_EQ(compute_tally(g, {manual})["s20"], "PROGRAM");
}

TEST(tally_manual_mapping_overrides_auto)
{
	auto g = base_graph();
	g.program = "Cena A51";
	TallyTarget t{"s20", std::string("Galaxy S20 FE"), "Cam A51"};
	CHECK_EQ(compute_tally(g, {t})["s20"], "PROGRAM");
	auto g2 = base_graph();
	g2.program = "Cena S20";
	CHECK_EQ(compute_tally(g2, {t})["s20"], "OFF");
}

// ---- transicao em andamento: origem segue PROGRAM, so-destino fica PREVIEW ate terminar
TEST(tally_transition_incoming_is_preview_outgoing_stays_program)
{
	auto g = base_graph();
	g.program = "Cena S20"; // o OBS ja reporta o DESTINO como programa no inicio da transicao
	g.in_transition = true;
	g.from_scene = "Cena A51";
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "PROGRAM"}, {"s20", "PREVIEW"}}));
	g.in_transition = false; // transicao terminou: destino vira PROGRAM, origem sai
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "OFF"}, {"s20", "PROGRAM"}}));
}

TEST(tally_transition_phone_in_both_scenes_stays_program)
{
	auto g = base_graph();
	g.scenes["Cena A51"] = {"Cam A51", "Cam S20"};
	g.program = "Cena S20";
	g.in_transition = true;
	g.from_scene = "Cena A51";
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "PROGRAM"}, {"s20", "PROGRAM"}}));
}

TEST(tally_transition_studio_mode_keeps_real_preview)
{
	auto g = base_graph();
	g.scenes["Cena C"] = {"Cam C"};
	g.ndi["Cam C"] = "BDSM (Celular C)";
	g.program = "Cena S20";
	g.preview = "Cena C"; // Modo Estudio: a previa continua PREVIEW durante a transicao
	g.in_transition = true;
	g.from_scene = "Cena A51";
	TallyTarget c{"c", std::string("Celular C"), ""};
	CHECK_EQ(compute_tally(g, {A51(), S20(), c}),
		 (Result{{"a51", "PROGRAM"}, {"s20", "PREVIEW"}, {"c", "PREVIEW"}}));
}

TEST(tally_transition_unknown_or_unresolvable_origin_uses_normal_rule)
{
	auto g = base_graph();
	g.program = "Cena S20";
	g.in_transition = true; // sem origem conhecida (ex.: vindo do preto)
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "OFF"}, {"s20", "PROGRAM"}}));
	g.from_scene = "Cena copia privada"; // nao e cena resolvivel por nome
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "OFF"}, {"s20", "PROGRAM"}}));
}

TEST(tally_transition_from_nested_scene_and_manual_mapping)
{
	auto g = base_graph();
	g.scenes["Mestre"] = {"Cena A51"};
	g.program = "Cena S20";
	g.in_transition = true;
	g.from_scene = "Mestre"; // origem aninha a cena do A51
	TallyTarget manual{"m", std::string("Galaxy A51"), "Cam S20"};
	CHECK_EQ(compute_tally(g, {A51(), S20(), manual}),
		 (Result{{"a51", "PROGRAM"}, {"s20", "PREVIEW"}, {"m", "PREVIEW"}}));
}

TEST(tally_transition_from_equals_destination)
{
	auto g = base_graph();
	g.program = "Cena A51";
	g.in_transition = true;
	g.from_scene = "Cena A51"; // re-corte para a mesma cena
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "PROGRAM"}, {"s20", "OFF"}}));
}

TEST(ndi_name_matches_stream_cases)
{
	CHECK(ndi_name_matches_stream("BDSM (Galaxy A51)", "Galaxy A51"));
	CHECK(ndi_name_matches_stream("LOCALHOST (Galaxy A51)", "Galaxy A51"));
	CHECK(ndi_name_matches_stream("  bdsm  (GALAXY   a51) ", "Galaxy A51"));
	CHECK(!ndi_name_matches_stream("BDSM (Galaxy A510)", "Galaxy A51"));
	CHECK(!ndi_name_matches_stream("BDSM (Galaxy A51)", ""));
	CHECK(!ndi_name_matches_stream("", "Galaxy A51"));
}

TEST(tally_two_phones_independent)
{
	auto g = base_graph();
	g.program = "Cena S20";
	g.preview = "Cena A51";
	CHECK_EQ(compute_tally(g, {A51(), S20()}), (Result{{"a51", "PREVIEW"}, {"s20", "PROGRAM"}}));
}

TEST(tally_none_program_scene)
{
	auto g = base_graph();
	CHECK_EQ(compute_tally(g, {A51()})["a51"], "OFF");
}

TEST(norm_accents_and_nbsp)
{
	CHECK_EQ(norm("C\xC3\xA2MERA"), norm("c\xC3\xA2mera"));           // "CâMERA" == "câmera"
	CHECK_EQ(norm("\xC3\x81" "gua"), "\xC3\xA1gua");                  // "Água" -> "água"
	CHECK_EQ(norm("a\xC2\xA0" "b"), "a b");                           // NBSP vira espaco
	CHECK_EQ(norm("   "), "");
}

TEST(battery_alert_once_with_hysteresis)
{
	BatteryAlert b;
	int seq[] = {50, 21, 20, 19, 18, 19, 20, 22, 19, 18};
	bool want[] = {false, false, false, true, false, false, false, false, false, false};
	for (int i = 0; i < 10; ++i)
		CHECK_EQ(b.update(seq[i]), want[i]);
	CHECK(!b.update(24));
	CHECK(!b.update(25)); // rearma ao atingir 25
	CHECK(b.update(19));  // nova descida: alerta de novo
	CHECK(!b.update(10));
}

// ---- LinkState ----
TEST(state_full_payload)
{
	LinkState s;
	CHECK(LinkState::from_json(
		"{\"deviceName\":\"Galaxy S20 FE\",\"batteryLevel\":88,\"isCharging\":true,"
		"\"captureSource\":\"C\\u00e2mera\",\"cameraLens\":\"Ultrawide\",\"fps\":30,"
		"\"microphone\":\"Microfone interno\",\"isRecording\":false,"
		"\"ndiStreamName\":\"Galaxy S20 FE\",\"tally\":\"PROGRAM\",\"campoNovo\":1}",
		s));
	CHECK_EQ(s.battery_level, 88);
	CHECK_EQ(s.fps, 30);
	CHECK_EQ(s.tally, "PROGRAM");
	CHECK_EQ(s.capture_source, "C\xC3\xA2mera");
	CHECK(s.is_charging);
	CHECK_EQ(*s.ndi_source_name(), "BDSM (Galaxy S20 FE)");
}

TEST(state_null_ndi_and_missing_fields)
{
	LinkState s;
	CHECK(LinkState::from_json("{\"ndiStreamName\": null, \"tally\": \"xyz\", \"batteryLevel\": \"oops\"}", s));
	CHECK(!s.ndi_stream_name.has_value());
	CHECK(!s.ndi_source_name().has_value());
	CHECK_EQ(s.tally, "OFF");
	CHECK_EQ(s.battery_level, 0);
	CHECK_EQ(s.device_name, "BDSM Device");
}

TEST(state_not_object_or_invalid)
{
	LinkState s;
	CHECK(!LinkState::from_json("[1,2]", s));
	CHECK(!LinkState::from_json("{nao e json", s));
	CHECK(!LinkState::from_json("", s));
}

TEST(state_clamps_and_lenient_types)
{
	LinkState s;
	CHECK(LinkState::from_json("{\"batteryLevel\": 250, \"fps\": \"60\", \"isRecording\": 1, \"tally\": \"preview\"}", s));
	CHECK_EQ(s.battery_level, 100);
	CHECK_EQ(s.fps, 60);          // int("60") do Python
	CHECK(!s.is_recording);       // so bool de verdade conta
	CHECK_EQ(s.tally, "PREVIEW"); // case-insensitive
	CHECK(LinkState::from_json("{\"batteryLevel\": -5, \"ndiStreamName\": \"   \"}", s));
	CHECK_EQ(s.battery_level, 0);
	CHECK(!s.ndi_stream_name.has_value()); // so espacos = NDI desligado
}

TEST(state_placeholder_frame)
{
	LinkState s;
	CHECK(LinkState::from_json("{\"deviceName\":\"BDSM Device\"}", s));
	CHECK(s.is_placeholder());
	CHECK(LinkState::from_json("{\"captureSource\":\"USB\",\"batteryLevel\":0}", s));
	CHECK(!s.is_placeholder());
}

TEST(tally_update_message_format)
{
	CHECK_EQ(tally_update_message("PROGRAM"), "{\"type\":\"TALLY_UPDATE\",\"state\":\"PROGRAM\"}");
	CHECK_EQ(tally_update_message("PREVIEW"), "{\"type\":\"TALLY_UPDATE\",\"state\":\"PREVIEW\"}");
	CHECK_EQ(tally_update_message("OFF"), "{\"type\":\"TALLY_UPDATE\",\"state\":\"OFF\"}");
	CHECK_EQ(tally_update_message("lixo"), "{\"type\":\"TALLY_UPDATE\",\"state\":\"OFF\"}");
	CHECK(tally_update_message("PROGRAM").size() < 1024);
}

// ---- util ----
TEST(parse_endpoint_cases)
{
	Endpoint e;
	CHECK(parse_endpoint("192.168.0.5", e));
	CHECK_EQ(e.host, "192.168.0.5");
	CHECK_EQ(e.port, 8080);
	CHECK(parse_endpoint(" 192.168.0.5:9000 ", e));
	CHECK_EQ(e.port, 9000);
	CHECK(parse_endpoint("http://10.0.0.2:8080/", e));
	CHECK_EQ(e.key(), "10.0.0.2:8080");
	CHECK(!parse_endpoint("10.0.0.2:abc", e));
	CHECK(!parse_endpoint("10.0.0.2:70000", e));
	CHECK(!parse_endpoint("10.0.0.2:0", e));
	CHECK(!parse_endpoint("", e));
	CHECK(!parse_endpoint("ho st", e));
}

TEST(backoff_caps_at_30)
{
	Backoff b;
	double seq[8];
	for (auto &d : seq)
		d = b.next();
	double want[] = {1, 2, 4, 8, 16, 30, 30, 30};
	for (int i = 0; i < 8; ++i)
		CHECK_EQ(seq[i], want[i]);
	b.reset();
	CHECK_EQ(b.next(), 1.0);
}

TEST(backoff_never_overflows)
{
	Backoff b;
	for (int i = 0; i < 100000; ++i)
		(void)b.next();
	CHECK_EQ(b.next(), 30.0);
}

TEST(client_name_limited_to_40_chars)
{
	CHECK_EQ(make_client_name("PC-LAC"), "OBS Studio (PC-LAC)");
	std::string n = make_client_name(std::string(100, 'x'));
	CHECK_EQ(n.size(), (size_t)40);
	CHECK_EQ(n.back(), ')');
	// nao corta no meio de um caractere UTF-8 (cada "\xC3\xA7" = 1 caractere)
	std::string acc;
	for (int i = 0; i < 40; ++i)
		acc += "\xC3\xA7";
	std::string m = make_client_name(acc);
	CHECK_EQ(utf8_truncate(m, 40).size(), m.size());
	CHECK_EQ(make_client_name(""), "OBS Studio (PC)");
}
