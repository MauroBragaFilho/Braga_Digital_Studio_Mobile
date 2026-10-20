#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace hd60s {

/** Um alt-setting de uma interface que contém o endpoint pedido. */
struct AltInfo {
    uint8_t alt = 0;
    bool present = false;       // o endpoint existe neste alt
    uint8_t transferType = 0;   // 1 = isócrono, 2 = bulk
    uint32_t packetBytes = 0;   // bytes por intervalo de serviço (maxp x mult x burst)
    uint8_t interval = 0;       // bInterval
};

/**
 * Percorre os descritores de configuração (como o usbfs os devolve em read()) e lista, por alt-setting
 * da interface `iface`, o que o endpoint `endpoint` oferece. O companion SuperSpeed (se houver) é levado
 * em conta; `superSpeed` indica como interpretar wMaxPacketSize.
 */
std::vector<AltInfo> parseAlts(const uint8_t* data, size_t length, uint8_t iface, uint8_t endpoint, bool superSpeed);

/** Como trazer o vídeo para a banda disponível: alt-setting e tipo de transferência. */
struct TransportPlan {
    bool valid = false;
    unsigned alt = 0;
    bool iso = false;
    uint32_t packetBytes = 0;  // só isócrono: bytes por intervalo de serviço
};

/**
 * Escolhe o alt-setting pela taxa de pixels (largura x altura x quadros/s), como o driver do Windows:
 * alt 1 até 4,1 Mpx/s (65,5 em SuperSpeed), alt 2 acima disso, e bulk (alt 4) quando nenhum alt
 * isócrono cabe. Cai para o que o dispositivo realmente oferece quando o alt desejado não existe.
 */
TransportPlan pickTransport(double pixelRate, bool superSpeed, const std::vector<AltInfo>& alts);

}  // namespace hd60s
