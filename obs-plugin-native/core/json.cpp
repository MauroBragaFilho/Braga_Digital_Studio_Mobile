#include "json.hpp"

#include <cstdlib>
#include <cstring>

namespace bdsm {

const Json *Json::find(const std::string &key) const
{
	if (type != Type::Object)
		return nullptr;
	for (size_t i = keys.size(); i > 0; --i)
		if (keys[i - 1] == key)
			return &items[i - 1];
	return nullptr;
}

namespace {

constexpr int kMaxDepth = 64;

class Parser {
public:
	explicit Parser(const std::string &t) : s_(t) {}

	bool parse(Json &out, std::string *err)
	{
		skip_ws();
		if (!value(out, 0))
			return fail(err);
		skip_ws();
		if (pos_ != s_.size()) {
			msg_ = "texto apos o valor JSON";
			return fail(err);
		}
		return true;
	}

private:
	const std::string &s_;
	size_t pos_ = 0;
	std::string msg_;

	bool fail(std::string *err)
	{
		if (err)
			*err = msg_.empty() ? "JSON invalido" : msg_;
		return false;
	}
	bool bad(const char *m)
	{
		msg_ = m;
		return false;
	}
	void skip_ws()
	{
		while (pos_ < s_.size() && (s_[pos_] == ' ' || s_[pos_] == '\t' || s_[pos_] == '\n' || s_[pos_] == '\r'))
			++pos_;
	}
	bool literal(const char *lit)
	{
		size_t n = std::strlen(lit);
		if (s_.compare(pos_, n, lit) != 0)
			return false;
		pos_ += n;
		return true;
	}
	static bool is_digit(char c) { return c >= '0' && c <= '9'; }

	bool value(Json &out, int depth)
	{
		if (depth > kMaxDepth)
			return bad("JSON profundo demais");
		if (pos_ >= s_.size())
			return bad("fim inesperado do JSON");
		char c = s_[pos_];
		switch (c) {
		case '{':
			return object(out, depth);
		case '[':
			return array(out, depth);
		case '"':
			out.type = Json::Type::String;
			return string(out.str);
		case 't':
			if (!literal("true"))
				return bad("literal invalido");
			out.type = Json::Type::Bool;
			out.boolean = true;
			return true;
		case 'f':
			if (!literal("false"))
				return bad("literal invalido");
			out.type = Json::Type::Bool;
			out.boolean = false;
			return true;
		case 'n':
			if (!literal("null"))
				return bad("literal invalido");
			out.type = Json::Type::Null;
			return true;
		default:
			if (c == '-' || is_digit(c))
				return number(out);
			return bad("caractere inesperado");
		}
	}

	bool number(Json &out)
	{
		size_t start = pos_;
		if (s_[pos_] == '-')
			++pos_;
		if (pos_ >= s_.size() || !is_digit(s_[pos_]))
			return bad("numero invalido");
		if (s_[pos_] == '0') {
			++pos_;
		} else {
			while (pos_ < s_.size() && is_digit(s_[pos_]))
				++pos_;
		}
		if (pos_ < s_.size() && s_[pos_] == '.') {
			++pos_;
			if (pos_ >= s_.size() || !is_digit(s_[pos_]))
				return bad("numero invalido");
			while (pos_ < s_.size() && is_digit(s_[pos_]))
				++pos_;
		}
		if (pos_ < s_.size() && (s_[pos_] == 'e' || s_[pos_] == 'E')) {
			++pos_;
			if (pos_ < s_.size() && (s_[pos_] == '+' || s_[pos_] == '-'))
				++pos_;
			if (pos_ >= s_.size() || !is_digit(s_[pos_]))
				return bad("numero invalido");
			while (pos_ < s_.size() && is_digit(s_[pos_]))
				++pos_;
		}
		out.type = Json::Type::Number;
		out.number = std::strtod(s_.substr(start, pos_ - start).c_str(), nullptr);
		return true;
	}

	static void append_utf8(std::string &o, unsigned cp)
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

	bool hex4(unsigned &v)
	{
		if (pos_ + 4 > s_.size())
			return false;
		v = 0;
		for (int i = 0; i < 4; ++i) {
			char c = s_[pos_ + (size_t)i];
			v <<= 4;
			if (c >= '0' && c <= '9')
				v |= (unsigned)(c - '0');
			else if (c >= 'a' && c <= 'f')
				v |= (unsigned)(c - 'a' + 10);
			else if (c >= 'A' && c <= 'F')
				v |= (unsigned)(c - 'A' + 10);
			else
				return false;
		}
		pos_ += 4;
		return true;
	}

	bool string(std::string &out)
	{
		out.clear();
		++pos_; // aspas de abertura
		while (true) {
			if (pos_ >= s_.size())
				return bad("string nao terminada");
			unsigned char c = (unsigned char)s_[pos_++];
			if (c == '"')
				return true;
			if (c < 0x20)
				return bad("caractere de controle em string");
			if (c != '\\') {
				out += (char)c;
				continue;
			}
			if (pos_ >= s_.size())
				return bad("escape truncado");
			char e = s_[pos_++];
			switch (e) {
			case '"': out += '"'; break;
			case '\\': out += '\\'; break;
			case '/': out += '/'; break;
			case 'b': out += '\b'; break;
			case 'f': out += '\f'; break;
			case 'n': out += '\n'; break;
			case 'r': out += '\r'; break;
			case 't': out += '\t'; break;
			case 'u': {
				unsigned cp;
				if (!hex4(cp))
					return bad("escape \\u invalido");
				if (cp >= 0xD800 && cp <= 0xDBFF) {
					unsigned lo;
					if (pos_ + 1 < s_.size() && s_[pos_] == '\\' && s_[pos_ + 1] == 'u') {
						pos_ += 2;
						if (!hex4(lo) || lo < 0xDC00 || lo > 0xDFFF)
							return bad("par substituto invalido");
						cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
					} else {
						return bad("par substituto incompleto");
					}
				} else if (cp >= 0xDC00 && cp <= 0xDFFF) {
					return bad("par substituto invalido");
				}
				append_utf8(out, cp);
				break;
			}
			default:
				return bad("escape desconhecido");
			}
		}
	}

	bool array(Json &out, int depth)
	{
		out.type = Json::Type::Array;
		++pos_;
		skip_ws();
		if (pos_ < s_.size() && s_[pos_] == ']') {
			++pos_;
			return true;
		}
		while (true) {
			skip_ws();
			Json item;
			if (!value(item, depth + 1))
				return false;
			out.items.push_back(std::move(item));
			skip_ws();
			if (pos_ >= s_.size())
				return bad("array nao terminado");
			if (s_[pos_] == ',') {
				++pos_;
				continue;
			}
			if (s_[pos_] == ']') {
				++pos_;
				return true;
			}
			return bad("esperado ',' ou ']'");
		}
	}

	bool object(Json &out, int depth)
	{
		out.type = Json::Type::Object;
		++pos_;
		skip_ws();
		if (pos_ < s_.size() && s_[pos_] == '}') {
			++pos_;
			return true;
		}
		while (true) {
			skip_ws();
			if (pos_ >= s_.size() || s_[pos_] != '"')
				return bad("esperada chave de objeto");
			std::string key;
			if (!string(key))
				return false;
			skip_ws();
			if (pos_ >= s_.size() || s_[pos_] != ':')
				return bad("esperado ':'");
			++pos_;
			skip_ws();
			Json v;
			if (!value(v, depth + 1))
				return false;
			out.keys.push_back(std::move(key));
			out.items.push_back(std::move(v));
			skip_ws();
			if (pos_ >= s_.size())
				return bad("objeto nao terminado");
			if (s_[pos_] == ',') {
				++pos_;
				continue;
			}
			if (s_[pos_] == '}') {
				++pos_;
				return true;
			}
			return bad("esperado ',' ou '}'");
		}
	}
};

} // namespace

bool parse_json(const std::string &text, Json &out, std::string *error)
{
	out = Json();
	Parser p(text);
	return p.parse(out, error);
}

std::string json_quote(const std::string &s)
{
	static const char *hex = "0123456789abcdef";
	std::string o = "\"";
	for (unsigned char c : s) {
		switch (c) {
		case '"': o += "\\\""; break;
		case '\\': o += "\\\\"; break;
		case '\n': o += "\\n"; break;
		case '\r': o += "\\r"; break;
		case '\t': o += "\\t"; break;
		default:
			if (c < 0x20) {
				o += "\\u00";
				o += hex[c >> 4];
				o += hex[c & 15];
			} else {
				o += (char)c;
			}
		}
	}
	o += '"';
	return o;
}

} // namespace bdsm
