// Framework de testes minimo (so std): sem FetchContent/rede no CI.
// Uso: TEST(nome) { CHECK(cond); CHECK_EQ(a, b); CHECK_THROWS(expr); }
#pragma once

#include <functional>
#include <iostream>
#include <sstream>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

namespace minitest {

struct Case {
	std::string name;
	std::function<void()> fn;
};

inline std::vector<Case> &registry()
{
	static std::vector<Case> r;
	return r;
}

inline int &failures()
{
	static int f = 0;
	return f;
}

struct Registrar {
	Registrar(const char *n, std::function<void()> f) { registry().push_back({n, std::move(f)}); }
};

template<class T, class = void> struct Streamable : std::false_type {};
template<class T>
struct Streamable<T, std::void_t<decltype(std::declval<std::ostream &>() << std::declval<const T &>())>>
	: std::true_type {};

template<class T> std::string show(const T &v)
{
	if constexpr (Streamable<T>::value) {
		std::ostringstream o;
		o << v;
		return o.str();
	} else {
		return "<valor nao imprimivel>";
	}
}
inline std::string show(const std::string &v) { return "\"" + v + "\""; }
inline std::string show(const char *v) { return std::string("\"") + v + "\""; }
inline std::string show(bool v) { return v ? "true" : "false"; }

} // namespace minitest

#define MT_CAT2(a, b) a##b
#define MT_CAT(a, b) MT_CAT2(a, b)

#define TEST(name)                                                                  \
	static void MT_CAT(test_fn_, name)();                                       \
	static minitest::Registrar MT_CAT(test_reg_, name)(#name, MT_CAT(test_fn_, name)); \
	static void MT_CAT(test_fn_, name)()

#define CHECK(cond)                                                                           \
	do {                                                                                  \
		if (!(cond)) {                                                                \
			++minitest::failures();                                               \
			std::cerr << "  FALHOU " << __FILE__ << ":" << __LINE__ << ": " #cond "\n"; \
		}                                                                             \
	} while (0)

#define CHECK_EQ(a, b)                                                                           \
	do {                                                                                     \
		auto mt_a = (a); /* copia: evita referencia pendente a temporarios */            \
		auto mt_b = (b);                                                                 \
		if (!(mt_a == mt_b)) {                                                           \
			++minitest::failures();                                                  \
			std::cerr << "  FALHOU " << __FILE__ << ":" << __LINE__ << ": " #a " == " #b \
				  << "\n    obtido:   " << minitest::show(mt_a)                  \
				  << "\n    esperado: " << minitest::show(mt_b) << "\n";         \
		}                                                                                \
	} while (0)

#define CHECK_THROWS(expr)                                                                   \
	do {                                                                                 \
		bool mt_threw = false;                                                       \
		try {                                                                        \
			expr;                                                                \
		} catch (...) {                                                              \
			mt_threw = true;                                                     \
		}                                                                            \
		if (!mt_threw) {                                                             \
			++minitest::failures();                                              \
			std::cerr << "  FALHOU " << __FILE__ << ":" << __LINE__ << ": esperava excecao em " #expr "\n"; \
		}                                                                            \
	} while (0)
