#include "Hd60sFrontEnd.h"

#include "Hd60sLog.h"

namespace hd60s {

namespace {

/**
 * Esqueleto de front-end ainda sem a inicialização do hardware.
 *
 * O QUE FALTA (por revisão), e onde implementar:
 *
 *  - start():       sequência de partida a frio da FX3 + MCU da placa e programação do receptor HDMI
 *                   (revisões 1-3: MStar MST3367 por barramento I2C tunelado em pedidos de fabricante;
 *                   revisão 4: o microcontrolador cuida do receptor).
 *  - detect():      leitura de geometria/taxa/colorimetria do sinal HDMI e, nas revisões 1-3, a aplicação
 *                   do modo no receptor.
 *  - beginStream()/endStream(): o "portão" que habilita a saída de vídeo da placa.
 *
 * Essas rotinas dependem de tabelas de registradores obtidas por engenharia reversa e precisam ser
 * escritas e validadas com a placa conectada. Enquanto isso, a sessão relata FrontEndMissing e o
 * Monitor mostra a mensagem correspondente, em vez de simular um sinal.
 */
class UnimplementedFrontEnd final : public FrontEnd {
public:
    explicit UnimplementedFrontEnd(uint16_t pid) : pid_(pid) {}

    const char* name() const override { return pid_ == kPidRev4 ? "HD60 S rev.4 (IT6802E)" : "HD60 S rev.1-3 (MST3367)"; }

    int start(UsbDevice&) override {
        LOGW("front-end %s: inicialização ainda não implementada", name());
        return kErrNotImplemented;
    }

    int detect(UsbDevice&, SignalInfo* out) override {
        *out = SignalInfo();
        return kErrNotImplemented;
    }

    int beginStream(UsbDevice&) override { return kErrNotImplemented; }
    int endStream(UsbDevice&) override { return kErrNotImplemented; }

private:
    uint16_t pid_;
};

}  // namespace

std::unique_ptr<FrontEnd> createMstarFrontEnd();  // Hd60sMstarFrontEnd.cpp

std::unique_ptr<FrontEnd> createFrontEnd(uint16_t productId) {
    switch (productId) {
        case kPidRev1:
        case kPidRev2:
        case kPidRev3:
            return createMstarFrontEnd();
        case kPidRev4:
            return std::make_unique<UnimplementedFrontEnd>(productId);
        default:
            return nullptr;
    }
}

}  // namespace hd60s
