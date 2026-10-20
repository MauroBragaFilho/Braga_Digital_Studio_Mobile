// Teste de host (sem Android) do parser do fluxo e do conversor YUY2->RGBA.
//   g++ -std=c++17 -O1 -Wall -I.. Hd60sHostTest.cpp ../Hd60sParser.cpp ../Hd60sConvert.cpp ../Hd60sDescriptors.cpp -o hd60s_test
#include <cstdio>
#include <cstring>
#include <vector>

#include "Hd60sConvert.h"
#include "Hd60sDescriptors.h"
#include "Hd60sParser.h"

using namespace hd60s;

static int failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FALHOU %s:%d  %s\n", __FILE__, __LINE__, #cond); \
            failures++;                                                          \
        }                                                                        \
    } while (0)

// Gera o marcador XY com a paridade BT.656 correta.
static uint8_t xy(int f, int v, int h) {
    const int p3 = v ^ h, p2 = f ^ h, p1 = f ^ v, p0 = f ^ v ^ h;
    return static_cast<uint8_t>(0x80 | (f << 6) | (v << 5) | (h << 4) | (p3 << 3) | (p2 << 2) | (p1 << 1) | p0);
}

struct Sink : FrameSink {
    std::vector<uint8_t> buf;
    int published = 0;
    bool noBuffer = false;
    explicit Sink(size_t bytes) : buf(bytes, 0xEE) {}
    uint8_t* acquire() override { return noBuffer ? nullptr : buf.data(); }
    void publish() override { published++; }
};

static void appendLine(std::vector<uint8_t>& s, uint8_t marker, uint32_t lineBytes, uint8_t fill) {
    s.push_back(0xFF);
    s.push_back(0x00);
    s.push_back(0x00);
    s.push_back(marker);
    s.insert(s.end(), lineBytes, fill);
}

// Um campo: `activeLines` ativas, depois `blankLines` de blanking.
static void appendField(std::vector<uint8_t>& s, int field, int activeLines, int blankLines, uint32_t lineBytes,
                        uint8_t (*fillFor)(int field, int line)) {
    for (int l = 0; l < activeLines; l++) appendLine(s, xy(field, 0, 0), lineBytes, fillFor(field, l));
    for (int l = 0; l < blankLines; l++) appendLine(s, xy(field, 1, 0), lineBytes, 0x77);
}

static uint8_t progressiveFill(int, int line) { return static_cast<uint8_t>(line + 1); }
static uint8_t interlacedFill(int field, int line) { return static_cast<uint8_t>(field * 100 + line + 1); }

static void feedInChunks(StreamParser& p, const std::vector<uint8_t>& s, size_t chunk) {
    for (size_t off = 0; off < s.size(); off += chunk) p.feed(s.data() + off, std::min(chunk, s.size() - off));
}

static void testTrc() {
    CHECK(StreamParser::trcValid(0x80));
    CHECK(StreamParser::trcValid(0xAB));
    CHECK(StreamParser::trcValid(0xC7));
    CHECK(StreamParser::trcValid(0xEC));
    CHECK(!StreamParser::trcValid(0x81));
    CHECK(!StreamParser::trcValid(0x00));
    for (int f = 0; f < 2; f++)
        for (int v = 0; v < 2; v++)
            for (int h = 0; h < 2; h++) CHECK(StreamParser::trcValid(xy(f, v, h)));
}

static void testProgressive() {
    Geometry g{64, 64, false};
    Sink sink(g.frameBytes());
    StreamParser p(g, &sink);

    std::vector<uint8_t> stream;
    appendField(stream, 0, 64, 4, g.lineBytes(), progressiveFill);
    // Fatiado em pedaços de 7 bytes: os registros atravessam as fronteiras dos pacotes.
    feedInChunks(p, stream, 7);
    // Um segundo quadro completo; o primeiro só é fechado ao entrar em blanking, o segundo idem.
    feedInChunks(p, stream, 1000);

    CHECK(sink.published == 2);
    CHECK(p.stats().frames == 2);
    CHECK(p.stats().resyncs == 0);
    CHECK(p.stats().parityErrors == 0);
    for (int l = 0; l < 64; l++) {
        CHECK(sink.buf[l * g.lineBytes()] == l + 1);
        CHECK(sink.buf[l * g.lineBytes() + g.lineBytes() - 1] == l + 1);
    }
}

static void testInterlacedWeave() {
    Geometry g{64, 64, true};
    Sink sink(g.frameBytes());
    StreamParser p(g, &sink);

    std::vector<uint8_t> stream;
    appendField(stream, 0, 32, 3, g.lineBytes(), interlacedFill);
    appendField(stream, 1, 32, 3, g.lineBytes(), interlacedFill);
    feedInChunks(p, stream, 513);

    CHECK(sink.published == 1);
    // Campo 0 nas linhas pares, campo 1 nas ímpares.
    for (int l = 0; l < 32; l++) {
        CHECK(sink.buf[(2 * l) * g.lineBytes()] == l + 1);
        CHECK(sink.buf[(2 * l + 1) * g.lineBytes()] == 100 + l + 1);
    }
}

static void testShortFrameIsAborted() {
    Geometry g{64, 64, false};
    Sink sink(g.frameBytes());
    StreamParser p(g, &sink);

    std::vector<uint8_t> stream;
    appendField(stream, 0, 50, 4, g.lineBytes(), progressiveFill);  // faltam 14 linhas
    p.feed(stream.data(), stream.size());

    CHECK(sink.published == 0);
    CHECK(p.stats().partial == 1);

    stream.clear();
    appendField(stream, 0, 64, 4, g.lineBytes(), progressiveFill);
    p.feed(stream.data(), stream.size());
    CHECK(sink.published == 1);  // o buffer abortado é reaproveitado
}

static void testResyncAndAudio() {
    Geometry g{64, 64, false};
    Sink sink(g.frameBytes());
    StreamParser p(g, &sink);

    std::vector<uint8_t> stream = {0x12, 0x34, 0x56, 0x78};  // lixo de 1 DWORD
    stream.insert(stream.end(), {0xFF, 0x00, 0xFF, 0x02});   // áudio: 2 DWORDs
    stream.insert(stream.end(), 8, 0xAA);
    appendField(stream, 0, 64, 4, g.lineBytes(), progressiveFill);
    feedInChunks(p, stream, 5);

    CHECK(p.stats().resyncs == 1);
    CHECK(p.stats().audioBytes == 8);
    CHECK(sink.published == 1);
}

static void testNoBufferDropsFrame() {
    Geometry g{64, 64, false};
    Sink sink(g.frameBytes());
    sink.noBuffer = true;
    StreamParser p(g, &sink);

    std::vector<uint8_t> stream;
    appendField(stream, 0, 64, 4, g.lineBytes(), progressiveFill);
    p.feed(stream.data(), stream.size());

    CHECK(sink.published == 0);
    CHECK(p.stats().dropped == 1);
}

static void testTolerantPublishesShortFrames() {
    Geometry g{64, 64, false};
    Sink sink(g.frameBytes());
    StreamParser p(g, &sink);
    p.setTolerant(true);

    std::vector<uint8_t> stream;
    appendField(stream, 0, 20, 4, g.lineBytes(), progressiveFill);  // só 20 das 64 linhas
    p.feed(stream.data(), stream.size());

    CHECK(sink.published == 1);
    CHECK(p.stats().partial == 1);
    CHECK(sink.buf[0] == 1 && sink.buf[19 * g.lineBytes()] == 20);
}

static void testStaleIgnoresStream() {
    Geometry g{64, 64, false};
    Sink sink(g.frameBytes());
    StreamParser p(g, &sink);
    p.setStale(true);

    std::vector<uint8_t> stream;
    appendField(stream, 0, 64, 4, g.lineBytes(), progressiveFill);
    p.feed(stream.data(), stream.size());
    CHECK(sink.published == 0);
}

static void yuy2Pixel(uint8_t y, uint8_t cb, uint8_t cr, ColorSpec c, uint8_t out[4]) {
    uint8_t src[4] = {y, cb, y, cr};
    uint8_t dst[8];
    yuy2ToRgba(src, 4, dst, 8, 2, 1, c);
    std::memcpy(out, dst, 4);
}

static void testConvert() {
    uint8_t px[4];
    ColorSpec limited709{true, false};

    yuy2Pixel(16, 128, 128, limited709, px);  // preto
    CHECK(px[0] == 0 && px[1] == 0 && px[2] == 0 && px[3] == 255);
    yuy2Pixel(235, 128, 128, limited709, px);  // branco
    CHECK(px[0] == 255 && px[1] == 255 && px[2] == 255);

    yuy2Pixel(126, 128, 128, limited709, px);  // cinza médio, R=G=B
    CHECK(px[0] == px[1] && px[1] == px[2]);
    CHECK(px[0] > 120 && px[0] < 135);

    yuy2Pixel(81, 90, 240, limited709, px);  // vermelho BT.601/709 aproximado: R alto, G/B baixos
    CHECK(px[0] > 200 && px[1] < 60 && px[2] < 60);

    ColorSpec full{true, true};
    yuy2Pixel(0, 128, 128, full, px);
    CHECK(px[0] == 0);
    yuy2Pixel(255, 128, 128, full, px);
    CHECK(px[0] == 255);

    // Largura/altura e stride separados: duas linhas.
    uint8_t src[2 * 8];
    for (int i = 0; i < 16; i += 4) {
        src[i] = 235;
        src[i + 1] = 128;
        src[i + 2] = 235;
        src[i + 3] = 128;
    }
    uint8_t dst[2 * 16];
    std::memset(dst, 0, sizeof(dst));
    yuy2ToRgba(src, 8, dst, 16, 4, 2, limited709);
    CHECK(dst[0] == 255 && dst[16] == 255 && dst[15] == 255);
}

// Descritores como o Android os mostrou para a HD60 S em USB 2.0: interface 0 com alts 0-2 isócronos
// (maxp 0, 0x0400 e 0x1400 = 3 transações por microframe) e a interface 1 com um endpoint de interrupção.
static std::vector<uint8_t> hs_descriptors() {
    std::vector<uint8_t> d = {9, 2, 0, 0, 2, 1, 0, 0x80, 0xfa};  // configuração (tamanho irrelevante)
    auto iface = [&](uint8_t n, uint8_t alt, uint8_t eps) {
        d.insert(d.end(), {9, 4, n, alt, eps, 0xff, 0, 0, 0});
    };
    auto ep = [&](uint8_t addr, uint8_t attr, uint16_t maxp, uint8_t interval) {
        d.insert(d.end(), {7, 5, addr, attr, (uint8_t)(maxp & 0xff), (uint8_t)(maxp >> 8), interval});
    };
    iface(0, 0, 1); ep(0x83, 0x01, 0x0000, 1);
    iface(0, 1, 1); ep(0x83, 0x01, 0x0400, 1);
    iface(0, 2, 1); ep(0x83, 0x01, 0x1400, 1);
    iface(1, 0, 1); ep(0x81, 0x03, 64, 1);
    return d;
}

static void testDescriptors() {
    const auto d = hs_descriptors();
    const auto alts = parseAlts(d.data(), d.size(), 0, 0x83, false);
    CHECK(alts.size() == 3);
    CHECK(alts[1].alt == 1 && alts[1].transferType == 1 && alts[1].packetBytes == 1024);
    CHECK(alts[2].alt == 2 && alts[2].transferType == 1 && alts[2].packetBytes == 3072);

    // 480i: 720x480 a 29,97 qd/s = ~10,4 Mpx/s -> alt 2 em USB 2.0.
    TransportPlan p = pickTransport(720.0 * 480 * 29.97, false, alts);
    CHECK(p.valid && p.alt == 2 && p.iso && p.packetBytes == 3072);
    // Sinal pequeno cabe no alt 1.
    p = pickTransport(2.0e6, false, alts);
    CHECK(p.valid && p.alt == 1);
    // 1080p60 em USB 2.0 não tem alt 3/4: usa o isócrono mais largo (vai perder quadros, mas capta).
    p = pickTransport(1920.0 * 1080 * 60, false, alts);
    CHECK(p.valid && p.alt == 2);
    // Sem alts utilizáveis.
    CHECK(!pickTransport(1e6, false, {}).valid);

    // SuperSpeed: com companion (maxBurst 15, mult 2, wBytesPerInterval 0) o alt bulk 4 serve ao 1080p60.
    std::vector<uint8_t> ss = {9, 2, 0, 0, 1, 1, 0, 0x80, 0xfa, 9, 4, 0, 4, 1, 0xff, 0, 0, 0};
    ss.insert(ss.end(), {7, 5, 0x83, 0x02, 0x00, 0x04, 0});
    ss.insert(ss.end(), {6, 0x30, 15, 0, 0, 0});
    const auto sa = parseAlts(ss.data(), ss.size(), 0, 0x83, true);
    CHECK(sa.size() == 1 && sa[0].alt == 4 && sa[0].transferType == 2 && sa[0].packetBytes == 1024 * 16);
    p = pickTransport(1920.0 * 1080 * 60, true, sa);
    CHECK(p.valid && p.alt == 4 && !p.iso);
}

int main() {
    testDescriptors();
    testTrc();
    testProgressive();
    testInterlacedWeave();
    testShortFrameIsAborted();
    testResyncAndAudio();
    testNoBufferDropsFrame();
    testStaleIgnoresStream();
    testTolerantPublishesShortFrames();
    testConvert();
    if (failures) {
        std::fprintf(stderr, "%d falha(s)\n", failures);
        return 1;
    }
    std::printf("OK\n");
    return 0;
}
