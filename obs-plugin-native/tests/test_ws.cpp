// Testes do codec WebSocket (WebSocketTests de test_unit.py) + handshake RFC 6455.
#include "minitest.hpp"
#include "ws_codec.hpp"

using namespace bdsm;

TEST(sha1_known_vectors)
{
	auto hex = [](const std::string &raw) {
		static const char *h = "0123456789abcdef";
		std::string o;
		for (unsigned char c : raw) {
			o += h[c >> 4];
			o += h[c & 15];
		}
		return o;
	};
	CHECK_EQ(hex(sha1_raw("abc")), "a9993e364706816aba3e25717850c26c9cd0d89d");
	CHECK_EQ(hex(sha1_raw("")), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
	CHECK_EQ(hex(sha1_raw("The quick brown fox jumps over the lazy dog")), "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12");
	// mais de um bloco de 64 bytes
	CHECK_EQ(hex(sha1_raw(std::string(1000, 'a'))), "291e9a6c66994949b57ba5e650361e98fc36b1ba");
}

TEST(base64_roundtrip_and_vectors)
{
	CHECK_EQ(base64_encode(""), "");
	CHECK_EQ(base64_encode("f"), "Zg==");
	CHECK_EQ(base64_encode("fo"), "Zm8=");
	CHECK_EQ(base64_encode("foo"), "Zm9v");
	CHECK_EQ(base64_encode("foobar"), "Zm9vYmFy");
	std::string raw;
	CHECK(base64_decode("Zm9vYmFy", raw));
	CHECK_EQ(raw, "foobar");
	CHECK(base64_decode("Zg==", raw));
	CHECK_EQ(raw, "f");
	CHECK(!base64_decode("@@@", raw));
	std::string bin;
	for (int i = 0; i < 256; ++i)
		bin += (char)i;
	CHECK(base64_decode(base64_encode(bin), raw));
	CHECK_EQ(raw, bin);
}

TEST(handshake_accept_rfc6455_example)
{
	// Exemplo da RFC 6455 secao 1.3
	CHECK_EQ(ws_accept_key("dGhlIHNhbXBsZSBub25jZQ=="), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
}

TEST(handshake_request_has_required_headers)
{
	std::string req = build_handshake_request("192.168.0.5", 8080, "/ws/link?token=abc", "KEY==");
	CHECK(req.find("GET /ws/link?token=abc HTTP/1.1\r\n") == 0);
	CHECK(req.find("Host: 192.168.0.5:8080\r\n") != std::string::npos);
	CHECK(req.find("Upgrade: websocket\r\n") != std::string::npos);
	CHECK(req.find("Sec-WebSocket-Version: 13\r\n") != std::string::npos);
	CHECK(req.find("Sec-WebSocket-Key: KEY==\r\n") != std::string::npos);
	CHECK(req.size() >= 4 && req.substr(req.size() - 4) == "\r\n\r\n");
}

TEST(handshake_response_ok_need_more_and_failures)
{
	std::string key = "dGhlIHNhbXBsZSBub25jZQ==";
	std::string ok = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
			 "sec-websocket-accept:  s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n";
	auto r = parse_handshake_response(ok + "RESTO", key);
	CHECK(r.state == HandshakeState::Ok);
	CHECK_EQ(r.consumed, ok.size());

	CHECK(parse_handshake_response(ok.substr(0, 30), key).state == HandshakeState::NeedMore);

	auto unauthorized = parse_handshake_response("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n", key);
	CHECK(unauthorized.state == HandshakeState::Failed);
	CHECK_EQ(unauthorized.http_status, 401);

	std::string bad = "HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: errado\r\n\r\n";
	auto b = parse_handshake_response(bad, key);
	CHECK(b.state == HandshakeState::Failed);
	CHECK_EQ(b.http_status, 0);

	auto huge = parse_handshake_response(std::string(20000, 'x'), key);
	CHECK(huge.state == HandshakeState::Failed);
}

TEST(client_frames_are_masked)
{
	std::string raw = encode_text_message("{\"type\":\"TALLY_UPDATE\",\"state\":\"PROGRAM\"}");
	CHECK((unsigned char)raw[1] & 0x80);
	CHECK(raw.find("TALLY_UPDATE") == std::string::npos); // payload mascarado
	auto f = FrameDecoder().feed(raw);
	CHECK_EQ(f.size(), (size_t)1);
	CHECK_EQ(f[0].opcode, OP_TEXT);
	CHECK_EQ(f[0].payload, "{\"type\":\"TALLY_UPDATE\",\"state\":\"PROGRAM\"}");
}

TEST(mask_roundtrip_with_fixed_key)
{
	std::string raw = encode_frame(OP_TEXT, "hello", true, true, std::string("\x01\x02\x03\x04", 4));
	CHECK((unsigned char)raw[1] & 0x80);
	CHECK(raw.find("hello") == std::string::npos);
	CHECK_EQ(FrameDecoder().feed(raw)[0].payload, "hello");
}

TEST(extended_lengths)
{
	for (size_t n : {(size_t)125, (size_t)126, (size_t)300, (size_t)70000}) {
		std::string raw = encode_frame(OP_TEXT, std::string(n, 'a'), true);
		auto f = FrameDecoder().feed(raw);
		CHECK_EQ(f.size(), (size_t)1);
		CHECK_EQ(f[0].payload.size(), n);
	}
}

TEST(send_over_8kb_rejected)
{
	CHECK_THROWS(encode_text_message(std::string(8 * 1024 + 1, 'x')));
	std::string ok = encode_text_message(std::string(8 * 1024, 'x')); // exatamente no limite passa
	CHECK(!ok.empty());
}

TEST(ping_gets_masked_pong)
{
	WsReceiver rx;
	rx.feed(encode_frame(OP_PING, "abc", false));
	CHECK(rx.messages.empty());
	auto out = FrameDecoder().feed(rx.outgoing);
	CHECK_EQ(out.size(), (size_t)1);
	CHECK_EQ(out[0].opcode, OP_PONG);
	CHECK_EQ(out[0].payload, "abc");
	CHECK((unsigned char)rx.outgoing[1] & 0x80);
}

TEST(text_message_utf8)
{
	WsReceiver rx;
	rx.feed(encode_frame(OP_TEXT, "ol\xC3\xA1", false));
	CHECK_EQ(rx.messages.size(), (size_t)1);
	CHECK_EQ(rx.messages[0], "ol\xC3\xA1");
}

TEST(fragmented_message_with_ping_in_between)
{
	WsReceiver rx;
	rx.feed(encode_frame(OP_TEXT, "{\"a\":", false, false));
	rx.feed(encode_frame(OP_PING, "", false));
	rx.feed(encode_frame(OP_CONT, "1,", false, false));
	rx.feed(encode_frame(OP_CONT, "\"b\":2}", false, true));
	CHECK_EQ(rx.messages.size(), (size_t)1);
	CHECK_EQ(rx.messages[0], "{\"a\":1,\"b\":2}");
}

TEST(partial_frame_across_reads_keeps_state)
{
	WsReceiver rx;
	std::string raw = encode_frame(OP_TEXT, "partido", false);
	rx.feed(raw.substr(0, 3));
	CHECK(rx.messages.empty());
	rx.feed(raw.substr(3));
	CHECK_EQ(rx.messages.size(), (size_t)1);
	CHECK_EQ(rx.messages[0], "partido");
}

TEST(close_frame_is_echoed_and_flags_closed)
{
	WsReceiver rx;
	std::string payload;
	payload += (char)(1008 >> 8);
	payload += (char)(1008 & 0xFF);
	payload += "Access revoked";
	rx.feed(encode_frame(OP_CLOSE, payload, false));
	CHECK(rx.closed);
	CHECK(rx.has_close_code);
	CHECK_EQ(rx.close_code, 1008); // 1008 = acesso revogado
	CHECK_EQ(rx.close_reason, "Access revoked");
	auto out = FrameDecoder().feed(rx.outgoing);
	CHECK_EQ(out.size(), (size_t)1);
	CHECK_EQ(out[0].opcode, OP_CLOSE);
}

TEST(decoder_rejects_oversize_rsv_and_bad_control)
{
	CHECK_THROWS(FrameDecoder(10).feed(encode_frame(OP_TEXT, std::string(11, 'x'), false)));
	CHECK_THROWS(FrameDecoder().feed(std::string("\xC1\x00", 2)));          // RSV1
	CHECK_THROWS(FrameDecoder().feed(encode_frame(OP_PING, "x", false, false))); // controle fragmentado
	CHECK_THROWS(FrameDecoder().feed(encode_frame(OP_PING, std::string(126, 'x'), false))); // controle > 125
}

TEST(receiver_protocol_errors)
{
	{
		WsReceiver rx;
		CHECK_THROWS(rx.feed(encode_frame(OP_CONT, "x", false))); // continuacao sem inicio
	}
	{
		WsReceiver rx;
		rx.feed(encode_frame(OP_TEXT, "a", false, false));
		CHECK_THROWS(rx.feed(encode_frame(OP_TEXT, "b", false))); // nova mensagem durante fragmentacao
	}
	{
		WsReceiver rx;
		CHECK_THROWS(rx.feed(std::string("\xC1\x00", 2)));
		CHECK(rx.sent_close); // pediu close 1002
		auto out = FrameDecoder().feed(rx.outgoing);
		CHECK_EQ(out.size(), (size_t)1);
		CHECK_EQ(out[0].opcode, OP_CLOSE);
	}
}
