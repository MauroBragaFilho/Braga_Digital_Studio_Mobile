#include "ws_codec.hpp"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <map>
#include <random>

namespace bdsm {

static const char *kGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

// ---------------------------------------------------------------- base64
static const char kB64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64_encode(const std::string &raw)
{
	std::string out;
	size_t i = 0;
	while (i + 2 < raw.size()) {
		unsigned v = ((unsigned char)raw[i] << 16) | ((unsigned char)raw[i + 1] << 8) | (unsigned char)raw[i + 2];
		out += kB64[(v >> 18) & 63];
		out += kB64[(v >> 12) & 63];
		out += kB64[(v >> 6) & 63];
		out += kB64[v & 63];
		i += 3;
	}
	if (i + 1 == raw.size()) {
		unsigned v = (unsigned char)raw[i] << 16;
		out += kB64[(v >> 18) & 63];
		out += kB64[(v >> 12) & 63];
		out += "==";
	} else if (i + 2 == raw.size()) {
		unsigned v = ((unsigned char)raw[i] << 16) | ((unsigned char)raw[i + 1] << 8);
		out += kB64[(v >> 18) & 63];
		out += kB64[(v >> 12) & 63];
		out += kB64[(v >> 6) & 63];
		out += '=';
	}
	return out;
}

bool base64_decode(const std::string &text, std::string &raw)
{
	raw.clear();
	unsigned acc = 0;
	int bits = 0;
	size_t pad = 0;
	for (char c : text) {
		if (c == '=') {
			++pad;
			continue;
		}
		if (pad)
			return false; // dado apos o padding
		const char *p = std::strchr(kB64, c);
		if (!p || c == '\0')
			return false;
		acc = (acc << 6) | (unsigned)(p - kB64);
		bits += 6;
		if (bits >= 8) {
			bits -= 8;
			raw += (char)((acc >> bits) & 0xFF);
		}
	}
	return pad <= 2;
}

// ---------------------------------------------------------------- SHA-1
static uint32_t rol(uint32_t v, int s) { return (v << s) | (v >> (32 - s)); }

std::string sha1_raw(const std::string &data)
{
	uint32_t h0 = 0x67452301, h1 = 0xEFCDAB89, h2 = 0x98BADCFE, h3 = 0x10325476, h4 = 0xC3D2E1F0;
	std::string msg = data;
	uint64_t bitlen = (uint64_t)data.size() * 8;
	msg += (char)0x80;
	while (msg.size() % 64 != 56)
		msg += (char)0;
	for (int i = 7; i >= 0; --i)
		msg += (char)((bitlen >> (i * 8)) & 0xFF);
	for (size_t off = 0; off < msg.size(); off += 64) {
		uint32_t w[80];
		for (int i = 0; i < 16; ++i) {
			const unsigned char *p = (const unsigned char *)msg.data() + off + (size_t)i * 4;
			w[i] = ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
		}
		for (int i = 16; i < 80; ++i)
			w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
		uint32_t a = h0, b = h1, c = h2, d = h3, e = h4;
		for (int i = 0; i < 80; ++i) {
			uint32_t f, k;
			if (i < 20) {
				f = (b & c) | (~b & d);
				k = 0x5A827999;
			} else if (i < 40) {
				f = b ^ c ^ d;
				k = 0x6ED9EBA1;
			} else if (i < 60) {
				f = (b & c) | (b & d) | (c & d);
				k = 0x8F1BBCDC;
			} else {
				f = b ^ c ^ d;
				k = 0xCA62C1D6;
			}
			uint32_t t = rol(a, 5) + f + e + k + w[i];
			e = d;
			d = c;
			c = rol(b, 30);
			b = a;
			a = t;
		}
		h0 += a;
		h1 += b;
		h2 += c;
		h3 += d;
		h4 += e;
	}
	std::string out;
	for (uint32_t h : {h0, h1, h2, h3, h4})
		for (int i = 3; i >= 0; --i)
			out += (char)((h >> (i * 8)) & 0xFF);
	return out;
}

std::string ws_accept_key(const std::string &client_key) { return base64_encode(sha1_raw(client_key + kGuid)); }

static void random_bytes(char *out, size_t n)
{
	static std::random_device rd;
	for (size_t i = 0; i < n; ++i)
		out[i] = (char)(rd() & 0xFF);
}

std::string ws_generate_key()
{
	char k[16];
	random_bytes(k, sizeof(k));
	return base64_encode(std::string(k, sizeof(k)));
}

// ---------------------------------------------------------------- handshake
std::string build_handshake_request(const std::string &host, int port, const std::string &path_and_query,
				    const std::string &client_key)
{
	return "GET " + path_and_query + " HTTP/1.1\r\nHost: " + host + ":" + std::to_string(port) +
	       "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + client_key +
	       "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: BDSM-Link-OBS\r\n\r\n";
}

static std::string lower(std::string s)
{
	for (auto &c : s)
		c = (char)std::tolower((unsigned char)c);
	return s;
}

static std::string trim(const std::string &s)
{
	size_t b = s.find_first_not_of(" \t");
	if (b == std::string::npos)
		return "";
	size_t e = s.find_last_not_of(" \t");
	return s.substr(b, e - b + 1);
}

HandshakeResult parse_handshake_response(const std::string &buf, const std::string &client_key)
{
	HandshakeResult r;
	size_t end = buf.find("\r\n\r\n");
	if (end == std::string::npos) {
		if (buf.size() > kMaxHandshakeBytes) {
			r.state = HandshakeState::Failed;
			r.error = "Resposta de handshake grande demais";
		}
		return r;
	}
	r.consumed = end + 4;
	std::string head = buf.substr(0, end);
	std::vector<std::string> lines;
	size_t pos = 0;
	while (true) {
		size_t nl = head.find("\r\n", pos);
		if (nl == std::string::npos) {
			lines.push_back(head.substr(pos));
			break;
		}
		lines.push_back(head.substr(pos, nl - pos));
		pos = nl + 2;
	}
	// "HTTP/1.1 101 Switching Protocols"
	const std::string &sl = lines[0];
	size_t sp1 = sl.find(' ');
	int status = 0;
	if (sp1 != std::string::npos) {
		size_t sp2 = sl.find(' ', sp1 + 1);
		std::string code = sl.substr(sp1 + 1, sp2 == std::string::npos ? std::string::npos : sp2 - sp1 - 1);
		if (!code.empty() && code.size() <= 3 &&
		    std::all_of(code.begin(), code.end(), [](char c) { return c >= '0' && c <= '9'; }))
			status = std::stoi(code);
	}
	r.http_status = status;
	if (status != 101) {
		r.state = HandshakeState::Failed;
		r.error = "Handshake WebSocket recusado: HTTP " + std::to_string(status);
		return r;
	}
	std::map<std::string, std::string> headers;
	for (size_t i = 1; i < lines.size(); ++i) {
		size_t c = lines[i].find(':');
		if (c == std::string::npos)
			continue;
		headers[lower(trim(lines[i].substr(0, c)))] = trim(lines[i].substr(c + 1));
	}
	auto it = headers.find("sec-websocket-accept");
	if (it == headers.end() || it->second != ws_accept_key(client_key)) {
		r.state = HandshakeState::Failed;
		r.http_status = 0;
		r.error = "Sec-WebSocket-Accept invalido";
		return r;
	}
	r.state = HandshakeState::Ok;
	return r;
}

// ---------------------------------------------------------------- quadros
std::string encode_frame(int opcode, const std::string &payload, bool mask, bool fin, const std::string &mask_key)
{
	std::string out;
	out += (char)((fin ? 0x80 : 0) | (opcode & 0x0F));
	size_t n = payload.size();
	unsigned char mbit = mask ? 0x80 : 0;
	if (n < 126) {
		out += (char)(mbit | n);
	} else if (n < 65536) {
		out += (char)(mbit | 126);
		out += (char)((n >> 8) & 0xFF);
		out += (char)(n & 0xFF);
	} else {
		out += (char)(mbit | 127);
		for (int i = 7; i >= 0; --i)
			out += (char)(((uint64_t)n >> (i * 8)) & 0xFF);
	}
	if (!mask)
		return out + payload;
	char key[4];
	if (mask_key.size() == 4)
		std::memcpy(key, mask_key.data(), 4);
	else
		random_bytes(key, 4);
	out.append(key, 4);
	for (size_t i = 0; i < n; ++i)
		out += (char)((unsigned char)payload[i] ^ (unsigned char)key[i % 4]);
	return out;
}

std::vector<Frame> FrameDecoder::feed(const char *data, size_t n)
{
	buf_.append(data, n);
	std::vector<Frame> frames;
	Frame f;
	while (try_one(f))
		frames.push_back(std::move(f));
	return frames;
}

bool FrameDecoder::try_one(Frame &f)
{
	if (buf_.size() < 2)
		return false;
	unsigned char b0 = (unsigned char)buf_[0], b1 = (unsigned char)buf_[1];
	bool fin = (b0 & 0x80) != 0;
	if (b0 & 0x70)
		throw WsError("Bits RSV inesperados");
	int opcode = b0 & 0x0F;
	bool masked = (b1 & 0x80) != 0;
	uint64_t n = b1 & 0x7F;
	size_t pos = 2;
	if (n == 126) {
		if (buf_.size() < 4)
			return false;
		n = ((uint64_t)(unsigned char)buf_[2] << 8) | (unsigned char)buf_[3];
		pos = 4;
	} else if (n == 127) {
		if (buf_.size() < 10)
			return false;
		n = 0;
		for (int i = 0; i < 8; ++i)
			n = (n << 8) | (unsigned char)buf_[2 + (size_t)i];
		pos = 10;
	}
	if (n > max_)
		throw WsError("Quadro grande demais (" + std::to_string(n) + " bytes)");
	if (opcode >= 0x8 && (n > 125 || !fin))
		throw WsError("Quadro de controle invalido");
	unsigned char key[4] = {0, 0, 0, 0};
	if (masked) {
		if (buf_.size() < pos + 4)
			return false;
		std::memcpy(key, buf_.data() + pos, 4);
		pos += 4;
	}
	if (buf_.size() < pos + (size_t)n)
		return false;
	f.fin = fin;
	f.opcode = opcode;
	f.payload.assign(buf_, pos, (size_t)n);
	buf_.erase(0, pos + (size_t)n);
	if (masked)
		for (size_t i = 0; i < f.payload.size(); ++i)
			f.payload[i] = (char)((unsigned char)f.payload[i] ^ key[i % 4]);
	return true;
}

// ---------------------------------------------------------------- mensagens
void WsReceiver::feed(const char *data, size_t n)
{
	std::vector<Frame> frames;
	try {
		frames = dec_.feed(data, n);
	} catch (const WsError &) {
		// 1002 = protocol error: o chamador envia este close antes de derrubar o socket
		if (!sent_close) {
			outgoing += encode_close(1002, "protocol error");
			sent_close = true;
		}
		throw;
	}
	for (const auto &f : frames) {
		if (closed)
			break;
		handle(f);
	}
}

void WsReceiver::handle(const Frame &f)
{
	switch (f.opcode) {
	case OP_PING:
		outgoing += encode_frame(OP_PONG, f.payload, true);
		return;
	case OP_PONG:
		return;
	case OP_CLOSE: {
		if (f.payload.size() >= 2) {
			has_close_code = true;
			close_code = ((unsigned char)f.payload[0] << 8) | (unsigned char)f.payload[1];
			close_reason = f.payload.substr(2);
		}
		if (!sent_close) { // devolve o quadro de close (eco do codigo)
			outgoing += encode_frame(OP_CLOSE, f.payload.substr(0, 2), true);
			sent_close = true;
		}
		closed = true;
		return;
	}
	case OP_TEXT:
	case OP_BINARY:
		if (frag_op_ >= 0)
			throw WsError("Nova mensagem durante fragmentacao");
		if (f.fin) {
			if (f.opcode == OP_TEXT)
				messages.push_back(f.payload);
			return;
		}
		frag_op_ = f.opcode;
		frag_ = f.payload;
		return;
	case OP_CONT:
		if (frag_op_ < 0)
			throw WsError("Continuacao sem inicio");
		frag_ += f.payload;
		if (frag_.size() > kMaxRecvMessage)
			throw WsError("Mensagem grande demais");
		if (f.fin) {
			if (frag_op_ == OP_TEXT)
				messages.push_back(frag_);
			frag_op_ = -1;
			frag_.clear();
		}
		return;
	default:
		throw WsError("Opcode desconhecido " + std::to_string(f.opcode));
	}
}

std::string encode_text_message(const std::string &text)
{
	if (text.size() > kMaxSendFrame)
		throw std::length_error("Quadro acima de 8 KB");
	return encode_frame(OP_TEXT, text, true);
}

std::string encode_close(int code, const std::string &reason)
{
	std::string p;
	p += (char)((code >> 8) & 0xFF);
	p += (char)(code & 0xFF);
	p += reason.substr(0, 100);
	return encode_frame(OP_CLOSE, p, true);
}

} // namespace bdsm
