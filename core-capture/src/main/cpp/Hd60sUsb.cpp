#include "Hd60sUsb.h"

#include <algorithm>
#include <chrono>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include "Hd60sLog.h"

namespace hd60s {

namespace {
constexpr uint8_t kDirOut = 0x00;
constexpr uint8_t kDirIn = 0x80;
constexpr uint8_t kTypeVendor = 0x40;
}  // namespace

int UsbDevice::control(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                       void* data, uint16_t length, unsigned timeoutMs) {
    usbdevfs_ctrltransfer ct;
    std::memset(&ct, 0, sizeof(ct));
    ct.bRequestType = requestType;
    ct.bRequest = request;
    ct.wValue = value;
    ct.wIndex = index;
    ct.wLength = length;
    ct.timeout = timeoutMs;
    ct.data = data;
    const int r = ::ioctl(fd_, USBDEVFS_CONTROL, &ct);
    if (r < 0) {
        const int e = errno;
        if (e == ETIMEDOUT) wedged_ = true;
        return -e;
    }
    return r;
}

int UsbDevice::vendorOut(uint8_t request, uint16_t value, uint16_t index, const void* data, uint16_t length) {
    if (wedged_) return -EIO;
    return control(kDirOut | kTypeVendor, request, value, index, const_cast<void*>(data), length);
}

int UsbDevice::vendorIn(uint8_t request, uint16_t value, uint16_t index, void* data, uint16_t length) {
    if (wedged_) return -EIO;
    return control(kDirIn | kTypeVendor, request, value, index, data, length);
}

int UsbDevice::claimInterface(unsigned iface) {
    return ::ioctl(fd_, USBDEVFS_CLAIMINTERFACE, &iface) < 0 ? -errno : 0;
}

int UsbDevice::releaseInterface(unsigned iface) {
    return ::ioctl(fd_, USBDEVFS_RELEASEINTERFACE, &iface) < 0 ? -errno : 0;
}

int UsbDevice::setConfiguration(int configuration) {
    return ::ioctl(fd_, USBDEVFS_SETCONFIGURATION, &configuration) < 0 ? -errno : 0;
}

int UsbDevice::setInterface(unsigned iface, unsigned alt) {
    usbdevfs_setinterface si;
    si.interface = iface;
    si.altsetting = alt;
    const int r = ::ioctl(fd_, USBDEVFS_SETINTERFACE, &si);
    if (r < 0) {
        const int e = errno;
        if (e == ETIMEDOUT) wedged_ = true;
        return -e;
    }
    return 0;
}

int UsbDevice::speed() const {
#ifdef USBDEVFS_GET_SPEED
    const int r = ::ioctl(fd_, USBDEVFS_GET_SPEED, 0);
    return r < 0 ? 0 : r;
#else
    return 0;
#endif
}

std::vector<uint8_t> UsbDevice::readDescriptors() const {
    std::vector<uint8_t> buf(4096);
    if (::lseek(fd_, 0, SEEK_SET) < 0) return {};
    const ssize_t n = ::read(fd_, buf.data(), buf.size());
    if (n <= 0) return {};
    buf.resize(static_cast<size_t>(n));
    return buf;
}

int UsbDevice::submit(usbdevfs_urb* urb) { return ::ioctl(fd_, USBDEVFS_SUBMITURB, urb) < 0 ? -errno : 0; }

int UsbDevice::discard(usbdevfs_urb* urb) { return ::ioctl(fd_, USBDEVFS_DISCARDURB, urb) < 0 ? -errno : 0; }

int UsbDevice::reap(usbdevfs_urb** out, int timeoutMs) {
    pollfd pfd;
    pfd.fd = fd_;
    pfd.events = POLLOUT;  // usbfs sinaliza URB concluído como "gravável"
    pfd.revents = 0;
    const int p = ::poll(&pfd, 1, timeoutMs);
    if (p < 0) return errno == EINTR ? -EAGAIN : -errno;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -ENODEV;
    if (p == 0) return -EAGAIN;
    void* ptr = nullptr;
    if (::ioctl(fd_, USBDEVFS_REAPURBNDELAY, &ptr) < 0) return -errno;
    *out = static_cast<usbdevfs_urb*>(ptr);
    return 0;
}

// ---------------------------------------------------------------------------------------------

UrbRing::UrbRing(UsbDevice& usb, uint8_t endpoint, size_t urbSize, int urbCount)
    : usb_(usb), endpoint_(endpoint), iso_(false), urbSize_(urbSize), urbCount_(urbCount) {}

UrbRing::UrbRing(UsbDevice& usb, uint8_t endpoint, uint32_t isoPacketBytes, int isoPackets, int urbCount)
    : usb_(usb), endpoint_(endpoint), iso_(true), urbSize_(static_cast<size_t>(isoPacketBytes) * isoPackets),
      isoPacketBytes_(isoPacketBytes), isoPackets_(isoPackets), urbCount_(urbCount) {}

UrbRing::~UrbRing() { stop(); }

bool UrbRing::start(Handler handler) {
    if (running_) return true;
    handler_ = std::move(handler);

    // Um URB isócrono grande é um kmalloc contíguo no kernel e pode falhar (ENOMEM) num aparelho
    // fragmentado: tenta de novo com metade dos pacotes (e o dobro de URBs, para a mesma folga).
    for (;;) {
        stats_.lastError = 0;
        if (tryStart()) return true;
        if (!iso_ || isoPackets_ <= 2 || stats_.lastError != -ENOMEM) return false;
        isoPackets_ /= 2;
        urbSize_ = static_cast<size_t>(isoPacketBytes_) * isoPackets_;
        urbCount_ = std::min(urbCount_ * 2, 64);
        LOGW("sem memória para os URBs; tentando %d pacotes por URB (%d URBs)", isoPackets_, urbCount_);
    }
}

bool UrbRing::tryStart() {
    urbs_.assign(urbCount_, nullptr);
    buffers_.assign(urbCount_, nullptr);
    inflight_ = 0;

    const size_t urbBytes = sizeof(usbdevfs_urb) + (iso_ ? isoPackets_ * sizeof(usbdevfs_iso_packet_desc) : 0);
    for (int i = 0; i < urbCount_; i++) {
        auto* urb = static_cast<usbdevfs_urb*>(std::calloc(1, urbBytes));
        auto* buf = static_cast<uint8_t*>(std::malloc(urbSize_));
        if (!urb || !buf) {
            std::free(urb);
            std::free(buf);
            break;
        }
        urb->type = iso_ ? USBDEVFS_URB_TYPE_ISO : USBDEVFS_URB_TYPE_BULK;
        urb->endpoint = endpoint_;
        urb->buffer = buf;
        urb->buffer_length = static_cast<int>(urbSize_);
        urb->usercontext = nullptr;
        if (iso_) {
            urb->flags = USBDEVFS_URB_ISO_ASAP;
            urb->number_of_packets = isoPackets_;
        }
        rearm(urb);
        urbs_[i] = urb;
        buffers_[i] = buf;
    }

    running_ = true;
    for (int i = 0; i < urbCount_; i++) {
        if (!urbs_[i]) continue;
        inflight_++;
        const int r = usb_.submit(urbs_[i]);
        if (r < 0) {
            inflight_--;
            stats_.lastError = r;
            LOGE("URB %d: submit falhou (%d)", i, r);
        }
    }
    if (inflight_ == 0) {
        running_ = false;
        for (int i = 0; i < urbCount_; i++) {
            std::free(urbs_[i]);
            std::free(buffers_[i]);
        }
        urbs_.clear();
        buffers_.clear();
        return false;
    }

    thread_ = std::thread([this] { reapLoop(); });
    return true;
}

// Prepara (ou reprepara) o URB para um novo envio.
void UrbRing::rearm(usbdevfs_urb* urb) {
    urb->status = 0;
    urb->actual_length = 0;
    urb->error_count = 0;
    if (iso_) {
        for (int p = 0; p < isoPackets_; p++) {
            urb->iso_frame_desc[p].length = isoPacketBytes_;
            urb->iso_frame_desc[p].actual_length = 0;
            urb->iso_frame_desc[p].status = 0;
        }
    }
}

// O usbfs aloca o buffer a cada SUBMITURB; num aparelho fragmentado isso falha às vezes com ENOMEM.
// Um URB perdido encolhe o anel para sempre (até secar e a FX3 transbordar), então ele fica na fila
// de pendentes e é reenviado a cada volta do laço.
void UrbRing::retryPending() {
    size_t n = pending_.size();
    while (n-- > 0) {
        usbdevfs_urb* urb = pending_.front();
        pending_.pop_front();
        if (usb_.submit(urb) == 0) {
            inflight_++;
        } else {
            pending_.push_back(urb);
        }
    }
}

void UrbRing::reapLoop() {
    int idleMs = 0;
    while (inflight_ > 0 || (running_ && !pending_.empty())) {
        if (!pending_.empty()) {
            if (running_) retryPending();
            if (inflight_ == 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }
        }
        usbdevfs_urb* urb = nullptr;
        const int r = usb_.reap(&urb, 100);
        if (r == -EAGAIN) {
            // Parado e nenhum URB volta (cancelamento perdido ou cabo puxado): desiste após 1,5 s.
            if (!running_) {
                idleMs += 100;
                if (idleMs >= 1500) {
                    LOGW("%d URB(s) não voltaram após o cancelamento", inflight_.load());
                    break;
                }
            }
            continue;
        }
        if (r < 0) {
            LOGE("reap falhou (%d); encerrando o anel", r);
            break;
        }
        idleMs = 0;
        inflight_--;
        stats_.urbs++;

        const int status = urb->status;
        if (status == -ENOENT || status == -ECONNRESET || status == -ESHUTDOWN) {
            continue;  // cancelado: sai do anel
        }

        const uint8_t* base = static_cast<const uint8_t*>(urb->buffer);
        if (iso_) {
            // Pacote com erro é um buraco num fluxo cujos registros atravessam pacotes; só se conta.
            for (int p = 0; p < isoPackets_; p++) {
                const auto& desc = urb->iso_frame_desc[p];
                if (desc.status != 0) {
                    stats_.errors++;
                    stats_.lastError = desc.status;
                    continue;
                }
                if (desc.actual_length > 0) {
                    stats_.bytes += desc.actual_length;
                    handler_(base + static_cast<size_t>(p) * isoPacketBytes_, desc.actual_length);
                }
            }
        } else if (status == 0) {
            if (urb->actual_length > 0) {
                stats_.bytes += urb->actual_length;
                handler_(base, static_cast<size_t>(urb->actual_length));
            }
        } else {
            stats_.errors++;
            stats_.lastError = status;
        }

        if (!running_) continue;
        rearm(urb);
        if (usb_.submit(urb) == 0) {
            inflight_++;
        } else {
            const uint32_t n = ++stats_.resubmitFailures;
            if (n <= 5 || n % 1000 == 0) LOGW("reenvio do URB falhou (%u falhas); fica na fila de pendentes", n);
            pending_.push_back(urb);
        }
    }
    inflight_ = 0;
    pending_.clear();
}

void UrbRing::stop() {
    if (!thread_.joinable() && urbs_.empty()) return;
    running_ = false;
    for (auto* urb : urbs_) {
        if (urb) usb_.discard(urb);
    }
    if (thread_.joinable()) thread_.join();
    for (int i = 0; i < static_cast<int>(urbs_.size()); i++) {
        std::free(urbs_[i]);
        std::free(buffers_[i]);
    }
    urbs_.clear();
    buffers_.clear();
}

}  // namespace hd60s
