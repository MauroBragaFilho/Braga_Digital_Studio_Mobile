#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <thread>
#include <vector>

struct usbdevfs_urb;

namespace hd60s {

/**
 * Acesso cru a um dispositivo USB pelo descritor de arquivo que o Android entrega
 * (UsbDeviceConnection.getFileDescriptor): ioctls do usbfs, sem libusb. O fd pertence ao chamador.
 */
class UsbDevice {
public:
    explicit UsbDevice(int fd) : fd_(fd) {}

    int fd() const { return fd_; }

    /** Transferência de controle. Retorna bytes transferidos ou -errno (-EPIPE = STALL, -ETIMEDOUT). */
    int control(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                void* data, uint16_t length, unsigned timeoutMs = 5000);

    /** Pedido de fabricante, dispositivo -> placa / placa -> dispositivo. */
    int vendorOut(uint8_t request, uint16_t value, uint16_t index, const void* data = nullptr, uint16_t length = 0);
    int vendorIn(uint8_t request, uint16_t value, uint16_t index, void* data, uint16_t length);

    int claimInterface(unsigned iface);
    int releaseInterface(unsigned iface);
    int setConfiguration(int configuration);
    int setInterface(unsigned iface, unsigned alt);

    /** 1=low 2=full 3=high 4=wireless 5=super, 0 = desconhecida. */
    int speed() const;

    /** Descritores de dispositivo + configuração como o usbfs os entrega em read(). Vazio se falhar. */
    std::vector<uint8_t> readDescriptors() const;

    /** Um timeout de controle deixa a FX3 travada: não adianta insistir até religar o cabo. */
    bool wedged() const { return wedged_; }

    // Primitivas de URB assíncrono (usadas por UrbRing).
    int submit(usbdevfs_urb* urb);
    int discard(usbdevfs_urb* urb);
    /** Colhe um URB concluído, esperando até timeoutMs. 0 = colheu, -EAGAIN = nada, -errno = erro. */
    int reap(usbdevfs_urb** out, int timeoutMs);

private:
    int fd_;
    std::atomic<bool> wedged_{false};
};

struct UrbStats {
    std::atomic<uint64_t> bytes{0};
    std::atomic<uint32_t> urbs{0};
    std::atomic<uint32_t> errors{0};
    std::atomic<int> lastError{0};
    std::atomic<uint32_t> resubmitFailures{0};
};

/**
 * Anel de URBs de entrada (EP 0x83): cada URB concluído é entregue ao handler e reenfileirado.
 * Bulk (um URB = um buffer) ou isócrono (um URB = `isoPackets` pacotes de `isoPacketBytes`).
 */
class UrbRing {
public:
    using Handler = std::function<void(const uint8_t* data, size_t length)>;

    /** Bulk. */
    UrbRing(UsbDevice& usb, uint8_t endpoint, size_t urbSize, int urbCount);

    /** Isócrono: o tamanho do URB é isoPacketBytes * isoPackets. */
    UrbRing(UsbDevice& usb, uint8_t endpoint, uint32_t isoPacketBytes, int isoPackets, int urbCount);

    ~UrbRing();

    /** Aloca, submete todos os URBs e inicia a thread que os colhe. false se nenhum pôde ser enviado. */
    bool start(Handler handler);

    /** Cancela os URBs e espera a thread terminar. Idempotente. */
    void stop();

    bool running() const { return running_; }
    bool isochronous() const { return iso_; }
    const UrbStats& stats() const { return stats_; }

private:
    bool tryStart();
    void reapLoop();
    void rearm(usbdevfs_urb* urb);
    void retryPending();

    UsbDevice& usb_;
    uint8_t endpoint_;
    bool iso_;
    size_t urbSize_;
    uint32_t isoPacketBytes_ = 0;
    int isoPackets_ = 0;
    int urbCount_;

    std::vector<usbdevfs_urb*> urbs_;
    std::vector<uint8_t*> buffers_;
    Handler handler_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<int> inflight_{0};
    std::deque<usbdevfs_urb*> pending_;  // URBs que não puderam voltar ao anel (só a thread do anel)
    UrbStats stats_;
};

}  // namespace hd60s
