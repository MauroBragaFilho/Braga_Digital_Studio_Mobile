#include "Hd60sConvert.h"

namespace hd60s {

namespace {

// Coeficientes em ponto fixo Q16.
struct Coeffs {
    int yScale;    // ganho de Y
    int yOffset;   // 16 (limitado) ou 0 (full)
    int rCr;
    int gCb;
    int gCr;
    int bCb;
};

constexpr int q16(double v) { return static_cast<int>(v * 65536.0 + (v >= 0 ? 0.5 : -0.5)); }

constexpr Coeffs kBt709Limited = {q16(255.0 / 219.0), 16, q16(1.5748 * 255.0 / 224.0), q16(-0.1873 * 255.0 / 224.0),
                                  q16(-0.4681 * 255.0 / 224.0), q16(1.8556 * 255.0 / 224.0)};
constexpr Coeffs kBt601Limited = {q16(255.0 / 219.0), 16, q16(1.4020 * 255.0 / 224.0), q16(-0.3441 * 255.0 / 224.0),
                                  q16(-0.7141 * 255.0 / 224.0), q16(1.7720 * 255.0 / 224.0)};
constexpr Coeffs kBt709Full = {q16(1.0), 0, q16(1.5748), q16(-0.1873), q16(-0.4681), q16(1.8556)};
constexpr Coeffs kBt601Full = {q16(1.0), 0, q16(1.4020), q16(-0.3441), q16(-0.7141), q16(1.7720)};

inline uint8_t clamp8(int v) { return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v)); }

}  // namespace

void yuy2ToRgba(const uint8_t* src, size_t srcStride, uint8_t* dst, size_t dstStride,
                uint32_t width, uint32_t height, ColorSpec color) {
    const Coeffs& k = color.fullRange ? (color.bt709 ? kBt709Full : kBt601Full)
                                      : (color.bt709 ? kBt709Limited : kBt601Limited);

    for (uint32_t y = 0; y < height; y++) {
        const uint8_t* s = src + y * srcStride;
        uint8_t* d = dst + y * dstStride;
        for (uint32_t x = 0; x + 1 < width; x += 2) {
            const int cb = s[1] - 128;
            const int cr = s[3] - 128;
            const int rAdd = (k.rCr * cr + 32768) >> 16;
            const int gAdd = (k.gCb * cb + k.gCr * cr + 32768) >> 16;
            const int bAdd = (k.bCb * cb + 32768) >> 16;

            for (int p = 0; p < 2; p++) {
                const int yy = (((s[p * 2] - k.yOffset) * k.yScale) + 32768) >> 16;
                d[0] = clamp8(yy + rAdd);
                d[1] = clamp8(yy + gAdd);
                d[2] = clamp8(yy + bAdd);
                d[3] = 255;
                d += 4;
            }
            s += 4;
        }
    }
}

}  // namespace hd60s
