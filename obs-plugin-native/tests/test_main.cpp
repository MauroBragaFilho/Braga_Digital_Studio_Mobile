#include "minitest.hpp"

int main()
{
	int failed_cases = 0;
	for (const auto &c : minitest::registry()) {
		int before = minitest::failures();
		try {
			c.fn();
		} catch (const std::exception &e) {
			++minitest::failures();
			std::cerr << "  EXCECAO em " << c.name << ": " << e.what() << "\n";
		} catch (...) {
			++minitest::failures();
			std::cerr << "  EXCECAO desconhecida em " << c.name << "\n";
		}
		bool ok = minitest::failures() == before;
		if (!ok)
			++failed_cases;
		std::cout << (ok ? "[ OK ] " : "[FAIL] ") << c.name << "\n";
	}
	std::cout << "\n" << minitest::registry().size() << " testes, " << failed_cases << " com falha\n";
	return failed_cases == 0 ? 0 : 1;
}
