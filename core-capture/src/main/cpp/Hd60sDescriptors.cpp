#include "Hd60sDescriptors.h"

namespace hd60s {

namespace {
constexpr uint8_t kTypeInterface = 0x04;
constexpr uint8_t kTypeEndpoint = 0x05;
constexpr uint8_t kTypeSsCompanion = 0x30;
}  // namespace

std::vector<AltInfo> parseAlts(const uint8_t* data, size_t length, uint8_t iface, uint8_t endpoint, bool superSpeed) {
    std::vector<AltInfo> alts;
    bool inIface = false;
    bool afterEndpoint = false;  // o próximo descritor pode ser o companion do endpoint encontrado
    uint32_t maxPacket = 0;

    size_t pos = 0;
    while (pos + 2 <= length) {
        const uint8_t len = data[pos];
        const uint8_t type = data[pos + 1];
        if (len < 2 || pos + len > length) break;
        const uint8_t* d = data + pos;

        if (type == kTypeInterface && len >= 9) {
            afterEndpoint = false;
            inIface = d[2] == iface;
            if (inIface) {
                AltInfo a;
                a.alt = d[3];
                alts.push_back(a);
            }
        } else if (type == kTypeEndpoint && len >= 7 && inIface && !alts.empty()) {
            afterEndpoint = false;
            if (d[2] == endpoint) {
                const uint16_t wMax = static_cast<uint16_t>(d[4] | (d[5] << 8));
                AltInfo& a = alts.back();
                a.present = true;
                a.transferType = d[3] & 0x03;
                a.interval = d[6];
                maxPacket = wMax & 0x7ff;
                // Alta velocidade: bits 12:11 = transações adicionais por microframe.
                a.packetBytes = superSpeed ? maxPacket : maxPacket * (((wMax >> 11) & 3) + 1u);
                afterEndpoint = true;
            }
        } else if (type == kTypeSsCompanion && len >= 6 && afterEndpoint && !alts.empty()) {
            AltInfo& a = alts.back();
            const uint16_t perInterval = static_cast<uint16_t>(d[4] | (d[5] << 8));
            const uint32_t burst = d[2] + 1u;
            const uint32_t mult = (a.transferType == 1) ? (d[3] & 3u) + 1u : 1u;
            a.packetBytes = perInterval ? perInterval : maxPacket * burst * mult;
            afterEndpoint = false;
        }
        pos += len;
    }
    return alts;
}

namespace {

const AltInfo* findAlt(const std::vector<AltInfo>& alts, unsigned alt) {
    for (const auto& a : alts) {
        if (a.alt == alt && a.present) return &a;
    }
    return nullptr;
}

TransportPlan planFrom(const AltInfo& a) {
    TransportPlan t;
    t.valid = true;
    t.alt = a.alt;
    t.iso = a.transferType == 1;
    t.packetBytes = a.packetBytes;
    return t;
}

}  // namespace

TransportPlan pickTransport(double pixelRate, bool superSpeed, const std::vector<AltInfo>& alts) {
    const double bw = pixelRate * 1.0451803;
    const double threshold = superSpeed ? 65536000.0 : 4096000.0;

    unsigned want;
    if (bw <= threshold) {
        want = 1;
    } else if (!superSpeed || bw <= 2 * threshold) {
        want = 2;
    } else {
        want = 3;  // nenhum alt isócrono comporta: bulk
    }

    if (want <= 2) {
        const AltInfo* a = findAlt(alts, want);
        if (a && a->transferType == 1 && a->packetBytes > 0) return planFrom(*a);
    }
    if (const AltInfo* b = findAlt(alts, 4)) {
        if (b->transferType == 2) return planFrom(*b);
    }
    // O alt desejado não existe: o isócrono mais largo que houver (melhor perder quadros que não captar).
    const AltInfo* best = nullptr;
    for (const auto& a : alts) {
        if (a.present && a.transferType == 1 && a.packetBytes > 0 && (!best || a.packetBytes > best->packetBytes)) {
            best = &a;
        }
    }
    return best ? planFrom(*best) : TransportPlan();
}

}  // namespace hd60s
