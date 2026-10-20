#pragma once

#include <cstddef>
#include <cstdint>

namespace hd60s {

struct ColorSpec {
    bool bt709 = true;        // false = BT.601 (vídeo SD)
    bool fullRange = false;   // false = limitado (16-235)
};

/** BT.709 para fontes HD e BT.601 para SD (altura do quadro <= 576), como as fontes costumam usar. */
inline ColorSpec defaultColorFor(uint32_t frameHeight) {
    ColorSpec c;
    c.bt709 = frameHeight > 576;
    return c;
}

/**
 * Converte YUY2 (Y0 Cb Y1 Cr) para RGBA 8888 (R,G,B,A em memória; alfa = 255).
 * `srcStride`/`dstStride` em bytes. `width` deve ser par.
 */
void yuy2ToRgba(const uint8_t* src, size_t srcStride, uint8_t* dst, size_t dstStride,
                uint32_t width, uint32_t height, ColorSpec color);

}  // namespace hd60s
