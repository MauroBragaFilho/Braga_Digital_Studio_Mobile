/* SPDX-License-Identifier: GPL-2.0
 *
 * Plano de controle e arranque a frio da Elgato HD60 S (revisões 1-3, receptor MStar MST3367).
 * Baseado no driver Linux "hd60s" (hd60s-core.c), Copyright (c) 2026 Doug Brown <doug@schmorgal.com>,
 * https://github.com/dougg3/hd60s-linux-driver (GPL-2.0), adaptado para C++/Android.
 * Este arquivo continua sob a GPL-2.0; veja LICENSE-HD60S-GPL2.txt nesta pasta.
 */
#include <memory>

#include "Hd60sFrontEnd.h"
#include "Hd60sPortTypes.h"

namespace hd60s {
std::unique_ptr<FrontEnd> createMstarFrontEnd();
}

// ---------------------------------------------------------------------------------------------
// Pedidos de fabricante e ponte I2C (usados pelo código portado)
// ---------------------------------------------------------------------------------------------

namespace {

constexpr u16 kChI2c = 0x5066;       // ponte I2C do microcontrolador
constexpr int kI2cMaxTx = 62;

// Escravos acessados direto pelo mestre I2C da FX3 (PLL 0x88, controle 0x98 e MST3367 0x9C);
// os demais passam pelo microcontrolador.
bool i2cDirect(u8 slave) { return ((u8)(slave + 0x78) & 0xeb) == 0 && slave != 0x8c; }

int normalize(int r) { return r < 0 ? r : 0; }

int gpioWrite(hd60s_dev* d, u8 gpio, u8 level) {
    return normalize(d->usb->vendorOut(HD60S_REQ_C1, (u16)((u16)level << 8 | gpio), 0));
}

int gpioRead(hd60s_dev* d, u8 gpio) {
    u8 b = 0;
    const int r = d->usb->vendorIn(HD60S_REQ_C1, gpio, 0, &b, 1);
    return r < 0 ? r : b;
}

// "AA|ler [rxlen] [tx...]" para o próprio microcontrolador.
int mcuWrite(hd60s_dev* d, const u8* tx, int txlen, int rxlen) {
    u8 buf[8];
    int n = 0;
    if (txlen + 2 > (int)sizeof(buf)) return -EINVAL;
    buf[n++] = (u8)(0xaa | (rxlen ? 1 : 0));
    if (rxlen) buf[n++] = (u8)rxlen;
    std::memcpy(buf + n, tx, txlen);
    n += txlen;
    return normalize(d->usb->vendorOut(HD60S_REQ_C0, kChI2c, 0, buf, (u16)n));
}

int mcuRead(hd60s_dev* d, u8* rx, int rxlen) {
    return normalize(d->usb->vendorIn(HD60S_REQ_C0, kChI2c, 0, rx, (u16)rxlen));
}

// O microcontrolador só responde depois de receber o comando; relê até dizer "51 10" (pronto).
int mcuWaitReady(hd60s_dev* d) {
    static const u8 cmdReady[3] = {0x12, 0x34, 0x57};
    u8 rx[3] = {};
    int errs = 0;

    for (int i = 0; i < 200 && !d->gone; i++) {
        int r = mcuWrite(d, cmdReady, sizeof(cmdReady), 3);
        if (r < 0) {
            LOGE("MCU: comando de pronto falhou (%d)", r);
            return r;
        }
        std::memset(rx, 0, sizeof(rx));
        r = mcuRead(d, rx, 3);
        if (r < 0) {
            if (++errs >= 5) {
                LOGE("MCU: 5 falhas seguidas na ponte (%d)", r);
                return r;
            }
            continue;
        }
        errs = 0;
        if (rx[0] == 0x51 && rx[1] == 0x10) break;
        if (rx[0] == 0x33 && rx[1] == 0x44 && rx[2] == 0x55) {
            msleep(101);
            continue;
        }
        LOGE("MCU respondeu %02x %02x %02x (esperado 51 10 ou 33 44 55)", rx[0], rx[1], rx[2]);
        return -ENODEV;
    }
    if (rx[0] != 0x51 || rx[1] != 0x10) {
        LOGE("MCU nunca ficou pronto (%02x %02x %02x)", rx[0], rx[1], rx[2]);
        return -ENODEV;
    }
    return 0;
}

void mcuLogVersion(hd60s_dev* d) {
    static const u8 cmdVer[3] = {0x12, 0x34, 0x58};
    u8 rx[3] = {};
    int errs = 0;
    for (int i = 0; i < 10 && !d->gone; i++) {
        msleep(101);
        if (!i && mcuWrite(d, cmdVer, sizeof(cmdVer), 3) < 0) return;
        std::memset(rx, 0, sizeof(rx));
        if (mcuRead(d, rx, 3) < 0) {
            if (++errs >= 5) return;
            continue;
        }
        LOGI("firmware do MCU: build 20%02u-%02u-%02u", rx[0], rx[1], rx[2]);
        return;
    }
}

int configRequests(hd60s_dev* d) {
    static const struct {
        u8 req;
        u16 val;
        const char* name;
    } seq[] = {
        {HD60S_REQ_C2, 0x0000, "C2 SetStreamPool"},
        {HD60S_REQ_C7, 0x0064, "C7 I2C config"},
    };
    for (const auto& s : seq) {
        const int r = hd60s_vout_nolock(d, s.req, s.val, 0x0000, nullptr, 0);
        if (r == -EPIPE) {
            LOGI("%s: STALL; seguindo (dispositivo já aquecido)", s.name);
            continue;
        }
        if (r < 0) {
            LOGE("%s falhou (%d)", s.name, r);
            return r;
        }
    }
    return 0;
}

// Arranque a frio (revisões 1-3: MCU depois do config). Termina com o front-end MST3367 inicializado.
int coldStart(hd60s_dev* d) {
    static const u8 ecPacket[10] = {0xb8, 0x22, 0x00, 0x00, 0x00, 0xc0, 0x00, 0x00, 0x50, 0xca};

    msleep(600);  // o driver do Windows emite 0xEC ~420 ms depois do SET_CONFIGURATION

    int r = hd60s_vout_nolock(d, HD60S_REQ_EC, 0x0000, 0x0000, ecPacket, sizeof(ecPacket));
    if (r == -EPIPE) {
        LOGI("0xEC: STALL; seguindo");
    } else if (r < 0) {
        LOGE("0xEC falhou (%d)", r);
        return r;
    }

    r = configRequests(d);
    if (r < 0) return r;

    gpioWrite(d, 0x39, 0xc0);  // GPIO 57: override + entrada
    usleep_range(2000, 3000);
    gpioWrite(d, 0x34, 0x41);  // GPIO 52: override + alto
    usleep_range(11000, 12000);

    // Em algumas unidades o override não pega e a leitura dá STALL: sai na primeira falha.
    int g = 0;
    for (int i = 0; i < 100; i++) {
        g = gpioRead(d, 0x39);
        if (g < 0 || g == 1) break;
        usleep_range(10000, 11000);
    }

    r = mcuWaitReady(d);
    if (r < 0) return r;
    mcuLogVersion(d);

    return hd60s_mstar_init(d);
}

}  // namespace

int hd60s_vout_nolock(hd60s_dev* d, u8 req, u16 val, u16 idx, const void* buf, u16 len) {
    return normalize(d->usb->vendorOut(req, val, idx, buf, len));
}

int hd60s_i2c_write(hd60s_dev* d, u8 slave, const u8* tx, int txlen) {
    u8 buf[1 + kI2cMaxTx];
    if (txlen < 1 || txlen > kI2cMaxTx) return -EINVAL;
    if (i2cDirect(slave)) {
        return normalize(d->usb->vendorOut(HD60S_REQ_C0, (u16)(0x5000 | slave), 0, tx, (u16)txlen));
    }
    buf[0] = slave & ~1;
    std::memcpy(buf + 1, tx, txlen);
    return normalize(d->usb->vendorOut(HD60S_REQ_C0, kChI2c, 0, buf, (u16)(txlen + 1)));
}

// Leitura é sempre pela ponte do microcontrolador, qualquer que seja o escravo.
int hd60s_i2c_read(hd60s_dev* d, u8 slave, const u8* tx, int txlen, u8* rx, int rxlen) {
    u8 buf[2 + kI2cMaxTx];
    if (txlen < 1 || txlen > kI2cMaxTx || rxlen < 1 || rxlen > 255) return -EINVAL;
    buf[0] = (slave & ~1) | 1;
    buf[1] = (u8)rxlen;
    std::memcpy(buf + 2, tx, txlen);
    int r = normalize(d->usb->vendorOut(HD60S_REQ_C0, kChI2c, 0, buf, (u16)(txlen + 2)));
    if (r < 0) return r;
    return normalize(d->usb->vendorIn(HD60S_REQ_C0, kChI2c, 0, rx, (u16)rxlen));
}

// ---------------------------------------------------------------------------------------------
// FrontEnd
// ---------------------------------------------------------------------------------------------

namespace hd60s {

namespace {

constexpr int kNoSignalDebounce = 3;

class MstarFrontEnd final : public FrontEnd {
public:
    const char* name() const override { return "HD60 S rev.1-3 (MST3367)"; }

    int start(UsbDevice& usb) override {
        dev_ = hd60s_dev();
        dev_.usb = &usb;
        noSignalPolls_ = 0;
        return coldStart(&dev_);
    }

    int detect(UsbDevice& usb, SignalInfo* out) override {
        dev_.usb = &usb;
        *out = SignalInfo();

        hd60s_timing t = {};
        bool signal = false;
        const int r = hd60s_mstar_detect(&dev_, &t, &signal);
        if (r < 0) return r;

        // O mesmo debounce do driver: um relock de ~1 s da fonte não vale como sinal perdido.
        if (signal) {
            noSignalPolls_ = 0;
            dev_.signal = true;
        } else if (++noSignalPolls_ >= kNoSignalDebounce) {
            dev_.signal = false;
        }
        applyColorRange();

        if (!signal) return 0;
        const bool il = hd60s_interlaced(&t);
        out->present = true;
        out->geometry.width = t.hactive;
        out->geometry.height = (uint32_t)t.vactive * (il ? 2 : 1);  // vactive é a altura do CAMPO se entrelaçado
        out->geometry.interlaced = il;
        double fps = il ? t.fps / 2.0 : t.fps;
        if (t.modeflag & HD60S_MF_FRACTIONAL) fps = fps * 1000.0 / 1001.0;
        out->fps = fps;
        out->bt709 = FIELD_GET(HD60S_MF_COLORIMETRY, t.modeflag) != 2;
        return 0;
    }

    int beginStream(UsbDevice& usb) override {
        dev_.usb = &usb;
        hd60s_mstar_disarm_events(&dev_);
        return hd60s_mstar_stream(&dev_, true);
    }

    int endStream(UsbDevice& usb) override {
        dev_.usb = &usb;
        hd60s_mstar_disarm_events(&dev_);
        return hd60s_mstar_stream(&dev_, false);
    }

private:
    // Entrada x saída desejada (limitada): faixa cheia na entrada é comprimida para 16-235.
    void applyColorRange() {
        const u8 cr = dev_.input_full ? HD60S_CR_SHRINK : HD60S_CR_BYPASS;
        if (cr == dev_.color_range) return;
        const u8 prev = dev_.color_range;
        dev_.color_range = cr;
        if (hd60s_mstar_write_csc(&dev_) < 0) dev_.color_range = prev;
    }

    hd60s_dev dev_;
    int noSignalPolls_ = 0;
};

}  // namespace

std::unique_ptr<FrontEnd> createMstarFrontEnd() { return std::make_unique<MstarFrontEnd>(); }

}  // namespace hd60s
