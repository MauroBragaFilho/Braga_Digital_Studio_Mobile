#include "Hd60sParser.h"

#include <algorithm>
#include <cstring>

namespace hd60s {

namespace {

constexpr uint8_t kMagic0 = 0xFF;
constexpr uint8_t kMagic1 = 0x00;
constexpr uint8_t kKindVideo = 0x00;
constexpr uint8_t kKindAudio = 0xFF;
constexpr uint8_t kBlankBit = 0x20;

/** YUY2 preto é 10 80 10 80 (zero seria verde brilhante). */
void fillBlack(uint8_t* frame, size_t bytes) {
    for (size_t i = 0; i + 1 < bytes; i += 2) {
        frame[i] = 0x10;
        frame[i + 1] = 0x80;
    }
}

}  // namespace

StreamParser::StreamParser(const Geometry& geometry, FrameSink* sink) : geometry_(geometry), sink_(sink) {}

bool StreamParser::trcValid(uint8_t xy) {
    if (!(xy & 0x80)) return false;
    const unsigned f = (xy >> 6) & 1;
    const unsigned v = (xy >> 5) & 1;
    const unsigned h = (xy >> 4) & 1;
    return (((xy >> 3) & 1) == (v ^ h)) && (((xy >> 2) & 1) == (f ^ h)) &&
           (((xy >> 1) & 1) == (f ^ v)) && ((xy & 1) == (f ^ v ^ h));
}

void StreamParser::abortFrame() {
    stats_.partial++;
    lines_ = 0;
    started_ = false;
    needsClear_ = true;  // faltam linhas: limpa antes de reaproveitar o buffer
}

void StreamParser::onMarker(uint8_t xy) {
    const bool blank = (xy & kBlankBit) != 0;
    const uint8_t field = (xy >> 6) & 1;

    if (!trcValid(xy)) stats_.parityErrors++;

    if (!blank && inBlank_) {
        // Entrando na região ativa.
        inBlank_ = false;
        field_ = field;

        if (!geometry_.interlaced || field == 0) {
            if (started_) abortFrame();
            if (!haveFrame_) {
                frame_ = sink_->acquire();
                haveFrame_ = frame_ != nullptr;
                if (!haveFrame_) stats_.dropped++;
            }
            if (frame_ && (geometry_.interlaced || tolerant_) && needsClear_) {
                fillBlack(frame_, geometry_.frameBytes());
                needsClear_ = false;
            }
            lines_ = 0;
            started_ = true;
        }
        // Campo 0 preenche as linhas pares a partir de 0; campo 1, as ímpares a partir de 1.
        dst_ = (geometry_.interlaced && field) ? geometry_.lineBytes() : 0;
    } else if (blank && !inBlank_) {
        // Saindo da região ativa.
        inBlank_ = true;
        if (started_ && lines_ == geometry_.height) {
            started_ = false;
            if (haveFrame_) {
                haveFrame_ = false;
                frame_ = nullptr;
                stats_.frames++;
                sink_->publish();
            }
        } else if (started_ && (!geometry_.interlaced || field == 1)) {
            if (tolerant_ && lines_ > 0 && haveFrame_) {
                // Quadro curto, mas mostrável: entrega assim mesmo e o próximo recomeça preto.
                started_ = false;
                needsClear_ = true;
                haveFrame_ = false;
                frame_ = nullptr;
                stats_.partial++;
                sink_->publish();
            } else {
                abortFrame();
            }
        }
    }
}

void StreamParser::feed(const uint8_t* data, size_t len) {
    stats_.bytes += len;
    if (stale_) return;

    const uint32_t lineBytes = geometry_.lineBytes();
    const uint32_t stride = lineBytes * (geometry_.interlaced ? 2 : 1);
    const size_t frameBytes = geometry_.frameBytes();

    while (len) {
        switch (state_) {
            case State::Header: {
                const size_t n = std::min<size_t>(4 - headerLen_, len);
                std::memcpy(header_ + headerLen_, data, n);
                headerLen_ = static_cast<uint8_t>(headerLen_ + n);
                data += n;
                len -= n;
                if (headerLen_ < 4) return;
                headerLen_ = 0;

                if (header_[0] != kMagic0 || header_[1] != kMagic1) {
                    // Registros são DWORDs inteiros: ressincroniza descartando 4 bytes, nunca byte a byte.
                    stats_.resyncs++;
                    break;
                }
                if (header_[2] == kKindAudio) {
                    remain_ = static_cast<uint32_t>(header_[3]) * 4;
                    state_ = State::Audio;
                } else if (header_[2] == kKindVideo) {
                    onMarker(header_[3]);
                    remain_ = lineBytes;
                    lineOff_ = 0;
                    state_ = (header_[3] & kBlankBit) ? State::Skip : State::Line;
                } else {
                    stats_.resyncs++;
                    break;
                }
                if (!remain_) state_ = State::Header;
                break;
            }
            case State::Line: {
                const size_t n = std::min<size_t>(remain_, len);
                if (frame_ && dst_ + lineOff_ + n <= frameBytes) {
                    std::memcpy(frame_ + dst_ + lineOff_, data, n);
                }
                lineOff_ += static_cast<uint32_t>(n);
                remain_ -= static_cast<uint32_t>(n);
                data += n;
                len -= n;
                if (!remain_) {
                    dst_ += stride;
                    lines_++;
                    state_ = State::Header;
                }
                break;
            }
            case State::Audio: {
                const size_t n = std::min<size_t>(remain_, len);
                sink_->audio(data, n);
                stats_.audioBytes += n;
                remain_ -= static_cast<uint32_t>(n);
                data += n;
                len -= n;
                if (!remain_) state_ = State::Header;
                break;
            }
            case State::Skip: {
                const size_t n = std::min<size_t>(remain_, len);
                remain_ -= static_cast<uint32_t>(n);
                data += n;
                len -= n;
                if (!remain_) state_ = State::Header;
                break;
            }
        }
    }
}

}  // namespace hd60s
