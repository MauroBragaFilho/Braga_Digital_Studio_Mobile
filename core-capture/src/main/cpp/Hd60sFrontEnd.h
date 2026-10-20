#pragma once

#include <cerrno>
#include <cstdint>
#include <memory>

#include "Hd60sParser.h"
#include "Hd60sUsb.h"

namespace hd60s {

constexpr uint16_t kVendorId = 0x0fd9;
constexpr uint16_t kPidRev1 = 0x004f;  // revisões 1-3: receptor HDMI MStar MST3367
constexpr uint16_t kPidRev2 = 0x005e;
constexpr uint16_t kPidRev3 = 0x0074;
constexpr uint16_t kPidRev4 = 0x0076;  // revisão 4: receptor ITE IT6802E gerido pelo microcontrolador

/** Código devolvido por um front-end cuja inicialização ainda não foi escrita. */
constexpr int kErrNotImplemented = -ENOSYS;

/** O que a placa informa sobre o que está chegando na entrada HDMI. */
struct SignalInfo {
    bool present = false;
    Geometry geometry;     // altura do QUADRO
    double fps = 0.0;      // quadros por segundo (campos/2 se entrelaçado)
    bool bt709 = true;     // colorimetria declarada pela fonte
};

/**
 * Tudo o que é específico do hardware de vídeo da placa: a inicialização a frio, a leitura do sinal
 * HDMI e o "portão" que liga/desliga o fluxo. Roda SEMPRE na thread de controle da sessão, nunca
 * concorrente com outro acesso de controle (a FX3 trava se receber transferências de controle
 * entrelaçadas com a troca de alt-setting).
 *
 * O transporte (alt-setting, anel de URBs, parser e renderização) é independente de revisão e vive
 * em Hd60sSession.
 */
class FrontEnd {
public:
    virtual ~FrontEnd() = default;

    virtual const char* name() const = 0;

    /** Inicialização a frio da placa. 0 em sucesso, -errno / kErrNotImplemented em falha. */
    virtual int start(UsbDevice& usb) = 0;

    /** Lê o estado do sinal HDMI (chamado a cada ~500 ms). */
    virtual int detect(UsbDevice& usb, SignalInfo* out) = 0;

    /** Liga o fluxo de vídeo da placa; chamado antes de selecionar o alt-setting do transporte. */
    virtual int beginStream(UsbDevice& usb) = 0;

    /** Desliga o fluxo; chamado depois de o transporte ter sido derrubado. */
    virtual int endStream(UsbDevice& usb) = 0;
};

/**
 * Front-end da revisão do dispositivo ou nullptr se o PID não é de uma HD60 S.
 *
 * PONTO A PREENCHER: nenhuma das revisões tem a inicialização escrita ainda (ver
 * Hd60sFrontEnd.cpp). Sem ela a placa enumera mas não envia vídeo.
 */
std::unique_ptr<FrontEnd> createFrontEnd(uint16_t productId);

}  // namespace hd60s
