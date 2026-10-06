// JSON minimo (parser + escape) sem dependencias: so std. Suficiente para o
// LinkState, as respostas de pareamento e o arquivo de configuracao.
#pragma once

#include <string>
#include <vector>

namespace bdsm {

struct Json {
	enum class Type { Null, Bool, Number, String, Array, Object };

	Type type = Type::Null;
	bool boolean = false;
	double number = 0.0;
	std::string str;
	std::vector<Json> items;       // Array: elementos; Object: valores
	std::vector<std::string> keys; // Object: chaves (mesma ordem de `items`)

	bool is_object() const { return type == Type::Object; }
	bool is_array() const { return type == Type::Array; }
	bool is_string() const { return type == Type::String; }
	bool is_number() const { return type == Type::Number; }
	bool is_bool() const { return type == Type::Bool; }
	bool is_null() const { return type == Type::Null; }

	// Ultimo valor com a chave (como o json do Python); nullptr se ausente.
	const Json *find(const std::string &key) const;
};

// Interpreta `text` como UM valor JSON (espacos nas pontas permitidos).
// Devolve false em erro de sintaxe, lixo apos o valor ou profundidade > 64.
bool parse_json(const std::string &text, Json &out, std::string *error = nullptr);

// Texto entre aspas com escapes JSON (UTF-8 passa direto; controles viram \u00XX).
std::string json_quote(const std::string &s);

} // namespace bdsm
