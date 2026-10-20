#pragma once

#include <cstddef>
#include <cstdint>

namespace hd60s {

/** Geometria de um quadro entregue pela placa (a placa não escala: é a da fonte HDMI). */
struct Geometry {
    uint32_t width = 0;
    uint32_t height = 0;       // altura do QUADRO (as duas metades, se entrelaçado)
    bool interlaced = false;

    uint32_t lineBytes() const { return width * 2; }              // YUY2: 2 bytes por pixel
    size_t frameBytes() const { return static_cast<size_t>(lineBytes()) * height; }
    bool valid() const { return width >= 64 && height >= 64 && width <= 4096 && height <= 4096; }
    bool operator==(const Geometry& o) const {
        return width == o.width && height == o.height && interlaced == o.interlaced;
    }
};

/** Quem fornece/recebe os buffers de quadro do parser. Chamado só na thread do parser. */
class FrameSink {
public:
    virtual ~FrameSink() = default;

    /** Buffer de frameBytes() para preencher, ou nullptr se não há nenhum livre (o quadro é descartado). */
    virtual uint8_t* acquire() = 0;

    /** O buffer devolvido por acquire() está completo. */
    virtual void publish() = 0;

    /** Áudio PCM s16 estéreo embutido no fluxo (ignorado por padrão). */
    virtual void audio(const uint8_t*, size_t) {}
};

struct ParserStats {
    uint64_t bytes = 0;
    uint32_t frames = 0;
    uint32_t dropped = 0;      // quadro completo, mas sem buffer livre
    uint32_t partial = 0;      // quadro abortado (linhas faltando)
    uint32_t resyncs = 0;      // cabeçalhos inválidos descartados
    uint32_t parityErrors = 0;
    uint64_t audioBytes = 0;
};

/**
 * Parser do fluxo da EP 0x83: sequência de registros alinhados em 32 bits.
 *
 *   FF 00 00 XY  linha de vídeo; XY é um código de referência BT.656 e `width*2` bytes de YUY2 seguem
 *                (também nas linhas de blanking, cujo payload é consumido e descartado).
 *   FF 00 FF nn  escape de áudio; seguem nn DWORDs de PCM s16 estéreo.
 *
 * XY: bit7 = 1, bit6 = F (campo), bit5 = V (blanking), bit4 = H. Registros cruzam livremente as
 * fronteiras dos pacotes USB, por isso é uma máquina de estados retomável, sem reagrupar buffers.
 */
class StreamParser {
public:
    StreamParser(const Geometry& geometry, FrameSink* sink);

    void feed(const uint8_t* data, size_t len);

    /** Para de interpretar (a fonte mudou de resolução): ignora o fluxo até o parser ser recriado. */
    void setStale(bool stale) { stale_ = stale; }
    bool stale() const { return stale_; }

    /**
     * Diagnóstico: publica também quadros incompletos (o resto fica preto). Para quando a banda do USB
     * não comporta o sinal e o fluxo chega truncado: a imagem sai rasgada, mas prova que o caminho
     * até a tela funciona.
     */
    void setTolerant(bool tolerant) { tolerant_ = tolerant; }

    const ParserStats& stats() const { return stats_; }
    const Geometry& geometry() const { return geometry_; }

    /** Paridade BT.656 do byte XY (auto-verificação do marcador). */
    static bool trcValid(uint8_t xy);

private:
    enum class State { Header, Line, Audio, Skip };

    void onMarker(uint8_t xy);
    void abortFrame();

    Geometry geometry_;
    FrameSink* sink_;
    ParserStats stats_;

    State state_ = State::Header;
    uint8_t header_[4] = {0, 0, 0, 0};
    uint8_t headerLen_ = 0;
    uint32_t remain_ = 0;      // bytes de payload ainda a consumir
    uint32_t lineOff_ = 0;     // bytes da linha atual já gravados
    size_t dst_ = 0;           // offset da linha atual no quadro

    bool inBlank_ = true;      // espera um começo real de campo
    bool started_ = false;     // há um quadro aberto
    bool needsClear_ = true;   // o buffer pode ter linhas antigas (entrelaçado incompleto)
    bool stale_ = false;
    bool tolerant_ = false;
    uint8_t field_ = 0;
    uint32_t lines_ = 0;
    uint8_t* frame_ = nullptr; // buffer em preenchimento (mantido ao abortar, é reaproveitado)
    bool haveFrame_ = false;
};

}  // namespace hd60s
