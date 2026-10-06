// Decisao pura do botao "Adicionar fonte NDI a cena atual" (core/ndi_link).
#include "minitest.hpp"
#include "ndi_link.hpp"

using namespace bdsm;

namespace {
const std::optional<std::string> kA51 = std::string("Galaxy A51");
}

TEST(friendly_name_basic_and_sanitized)
{
	CHECK_EQ(friendly_source_name("Galaxy A51"), "BDSM - Galaxy A51");
	CHECK_EQ(friendly_source_name("  Galaxy A51 \t\n"), "BDSM - Galaxy A51");
	CHECK_EQ(friendly_source_name(""), "BDSM - Celular");
	CHECK_EQ(friendly_source_name("   "), "BDSM - Celular");
	CHECK_EQ(friendly_source_name("C\xC3\xA2mera \xC3\xA7"), "BDSM - C\xC3\xA2mera \xC3\xA7"); // UTF-8 preservado
}

TEST(friendly_name_truncates_without_splitting_utf8)
{
	std::string longName(70, 'x');
	CHECK_EQ(friendly_source_name(longName).size(), (size_t)(7 + 64));
	std::string u; // 40 x "ã" = 80 bytes; o corte em 64 cai no limite de um caractere
	for (int i = 0; i < 40; ++i)
		u += "\xC3\xA3";
	CHECK_EQ(friendly_source_name(u).size(), (size_t)(7 + 64));
	std::string odd = "a"; // 81 bytes: o byte 64 e o 2o byte de um "ã" -> recua um caractere
	odd += u;
	std::string g = friendly_source_name(odd).substr(7);
	CHECK(g.size() <= 64);
	CHECK_EQ(g.size(), (size_t)63);
	CHECK_EQ((unsigned char)g[g.size() - 1], 0xA3u);
	CHECK_EQ((unsigned char)g[g.size() - 2], 0xC3u); // termina num par completo (sem metade solta)
}

TEST(unique_name_suffixes)
{
	CHECK_EQ(unique_source_name("BDSM - A", {}), "BDSM - A");
	CHECK_EQ(unique_source_name("BDSM - A", {"BDSM - A"}), "BDSM - A (2)");
	CHECK_EQ(unique_source_name("BDSM - A", {"BDSM - A", "BDSM - A (2)"}), "BDSM - A (3)");
}

TEST(plan_no_ndi_name)
{
	auto p = plan_add_ndi_source("A51", std::nullopt, "Cena 1", {}, {});
	CHECK(p.action == AddAction::NoNdiName);
	auto q = plan_add_ndi_source("A51", std::string(""), "Cena 1", {}, {});
	CHECK(q.action == AddAction::NoNdiName);
}

TEST(plan_create_when_missing_with_unique_name)
{
	std::vector<ObsNdiSourceInfo> src = {{"Outra", "BDSM (S20)", {"Cena 1"}}};
	auto p = plan_add_ndi_source("Galaxy A51", kA51, "Cena 1", src, {"Outra", "Cena 1", "BDSM - Galaxy A51"});
	CHECK(p.action == AddAction::CreateNew);
	CHECK_EQ(p.source_name, "BDSM - Galaxy A51 (2)"); // nome ja usado por outra fonte/cena
	CHECK_EQ(p.ndi_name, "BDSM (Galaxy A51)");
}

TEST(plan_already_in_target_scene_does_not_duplicate)
{
	std::vector<ObsNdiSourceInfo> src = {{"Cam A51", "BDSM (Galaxy A51)", {"Cena 1", "Cena 2"}}};
	auto p = plan_add_ndi_source("Galaxy A51", kA51, "Cena 2", src, {"Cam A51"});
	CHECK(p.action == AddAction::AlreadyInScene);
	CHECK_EQ(p.source_name, "Cam A51");
	CHECK_EQ(p.in_scenes.size(), (size_t)2);
}

TEST(plan_reuses_existing_from_other_scene)
{
	std::vector<ObsNdiSourceInfo> src = {{"Cam A51", "BDSM (Galaxy A51)", {"Cena 1"}}};
	auto p = plan_add_ndi_source("Galaxy A51", kA51, "Cena 2", src, {"Cam A51"});
	CHECK(p.action == AddAction::AddExisting);
	CHECK_EQ(p.source_name, "Cam A51");
}

TEST(plan_reuses_existing_not_in_any_scene)
{
	std::vector<ObsNdiSourceInfo> src = {{"Orfa", "BDSM (Galaxy A51)", {}}};
	auto p = plan_add_ndi_source("Galaxy A51", kA51, "Cena 1", src, {"Orfa"});
	CHECK(p.action == AddAction::AddExisting);
	CHECK_EQ(p.source_name, "Orfa");
	CHECK(p.in_scenes.empty());
}

TEST(plan_matches_machine_prefix_case_and_spaces)
{
	std::vector<ObsNdiSourceInfo> src = {{"Cam", "  localhost  (GALAXY  a51)", {"Cena 1"}}};
	auto p = plan_add_ndi_source("Galaxy A51", kA51, "Cena 1", src, {"Cam"});
	CHECK(p.action == AddAction::AlreadyInScene);
}

TEST(plan_no_false_positive_other_phone)
{
	std::vector<ObsNdiSourceInfo> src = {{"Cam", "BDSM (Galaxy A510)", {"Cena 1"}}};
	auto p = plan_add_ndi_source("Galaxy A51", kA51, "Cena 1", src, {"Cam"});
	CHECK(p.action == AddAction::CreateNew);
}

TEST(plan_prefers_exact_then_target_scene_then_any_scene_then_name)
{
	std::vector<ObsNdiSourceInfo> src = {
		{"B-prefixo", "OUTRO (Galaxy A51)", {"Cena 1"}},
		{"A-exata-fora", "BDSM (Galaxy A51)", {"Cena 9"}},
		{"C-prefixo-sem-cena", "OUTRO (Galaxy A51)", {}},
	};
	auto p = plan_add_ndi_source("Galaxy A51", kA51, "Cena 1", src, {});
	CHECK_EQ(p.source_name, "A-exata-fora"); // exata ganha de tudo
	CHECK(p.action == AddAction::AddExisting);

	std::vector<ObsNdiSourceInfo> two = {
		{"Z", "BDSM (Galaxy A51)", {"Cena 9"}},
		{"Y", "BDSM (Galaxy A51)", {"Cena 1"}},
	};
	auto q = plan_add_ndi_source("Galaxy A51", kA51, "Cena 1", two, {});
	CHECK_EQ(q.source_name, "Y"); // ja na cena alvo ganha
	CHECK(q.action == AddAction::AlreadyInScene);

	std::vector<ObsNdiSourceInfo> three = {
		{"M", "BDSM (Galaxy A51)", {"Cena 9"}},
		{"K", "BDSM (Galaxy A51)", {"Cena 8"}},
	};
	auto r = plan_add_ndi_source("Galaxy A51", kA51, "Cena 1", three, {});
	CHECK_EQ(r.source_name, "K"); // empate: ordem alfabetica (determinista)
}
