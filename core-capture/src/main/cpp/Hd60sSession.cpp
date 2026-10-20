#include "Hd60sSession.h"

#include <android/native_window.h>

#include <algorithm>
#include <cstdio>
#include <chrono>
#include <deque>
#include <vector>

#include "Hd60sConvert.h"
#include "Hd60sDescriptors.h"
#include "Hd60sLog.h"
#include "Hd60sParser.h"

namespace hd60s {

namespace {

constexpr int kVideoInterface = 0;
constexpr int kEventInterface = 1;       // reivindicada para que nada mais a use; nunca é aberta
constexpr uint8_t kVideoEndpoint = 0x83;
constexpr size_t kBulkUrbSize = 32 * 1024;
constexpr int kIsoPacketsPerUrb = 8;  // URBs pequenos: o kmalloc contíguo do usbfs falha menos
constexpr int kBulkUrbCount = 16;
constexpr int kIsoUrbCount = 64;
constexpr int kPollMs = 500;
constexpr int kNoSignalDebounce = 3;     // leituras sem sinal antes de dar o sinal como perdido
constexpr int kGateSettleMs = 105;       // entre ligar o portão de vídeo e selecionar o alt-setting
constexpr int kTeardownSettleMs = 35;    // entre derrubar o transporte e desligar o portão

}  // namespace

// ---------------------------------------------------------------------------------------------

/** Quadros prontos entre o parser (thread do anel de URBs) e o renderizador. */
class Session::FramePool final : public FrameSink {
public:
    explicit FramePool(size_t frameBytes, int count = 4) : bufs_(count, std::vector<uint8_t>(frameBytes)) {
        for (int i = 0; i < count; i++) free_.push_back(i);
    }

    uint8_t* acquire() override {
        std::lock_guard<std::mutex> lk(m_);
        if (free_.empty()) return nullptr;
        filling_ = free_.front();
        free_.pop_front();
        return bufs_[filling_].data();
    }

    void publish() override {
        {
            std::lock_guard<std::mutex> lk(m_);
            if (filling_ < 0) return;
            if (ready_ >= 0) free_.push_back(ready_);  // o renderizador não alcançou: fica o mais novo
            ready_ = filling_;
            filling_ = -1;
        }
        cv_.notify_one();
    }

    /** Bloqueia até haver um quadro pronto; nullptr quando `quit` é sinalizado. */
    const uint8_t* take(const std::atomic<bool>& quit) {
        std::unique_lock<std::mutex> lk(m_);
        cv_.wait(lk, [&] { return ready_ >= 0 || quit.load(); });
        if (ready_ < 0) return nullptr;
        taken_ = ready_;
        ready_ = -1;
        return bufs_[taken_].data();
    }

    void release() {
        std::lock_guard<std::mutex> lk(m_);
        if (taken_ >= 0) free_.push_back(taken_);
        taken_ = -1;
    }

    void wake() { cv_.notify_all(); }

private:
    std::mutex m_;
    std::condition_variable cv_;
    std::vector<std::vector<uint8_t>> bufs_;
    std::deque<int> free_;
    int filling_ = -1;
    int ready_ = -1;
    int taken_ = -1;
};

struct Session::Stream {
    Stream(UsbDevice& usb, const Geometry& geometry, ColorSpec colorSpec, const TransportPlan& plan)
        : g(geometry), color(colorSpec), pool(geometry.frameBytes()), parser(geometry, &pool),
          bulk(plan.iso ? UrbRing(usb, kVideoEndpoint, plan.packetBytes, kIsoPacketsPerUrb, kIsoUrbCount)
                        : UrbRing(usb, kVideoEndpoint, kBulkUrbSize, kBulkUrbCount)) {}

    Geometry g;
    ColorSpec color;
    FramePool pool;
    StreamParser parser;
    UrbRing bulk;
    std::thread renderer;
    std::atomic<bool> quit{false};
    int appliedWindowGeneration = -1;
    int dumps = 0;
};

// ---------------------------------------------------------------------------------------------

Session::Session(int usbFd, uint16_t productId) : usb_(usbFd), pid_(productId) {}

Session::~Session() { stop(); }

bool Session::start(ANativeWindow* window) {
    if (thread_.joinable()) {
        if (window) ANativeWindow_release(window);
        return false;
    }
    setWindow(window);
    quit_ = false;
    state_ = static_cast<int>(SessionState::Initializing);
    fault_ = static_cast<int>(Fault::None);
    thread_ = std::thread([this] { controlLoop(); });
    return true;
}

void Session::setWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lk(windowMutex_);
    if (window_) ANativeWindow_release(window_);
    window_ = window;
    windowGeneration_++;
}

void Session::stop() {
    quit_ = true;
    waitCv_.notify_all();
    if (thread_.joinable()) thread_.join();
    setWindow(nullptr);
    state_ = static_cast<int>(SessionState::Idle);
}

bool Session::waitFor(int ms) {
    std::unique_lock<std::mutex> lk(waitMutex_);
    waitCv_.wait_for(lk, std::chrono::milliseconds(ms), [this] { return quit_.load(); });
    return !quit_;
}

void Session::fail(Fault fault) {
    LOGE("sessão encerrada com falha %d", static_cast<int>(fault));
    fault_ = static_cast<int>(fault);
    state_ = static_cast<int>(SessionState::Error);
}

Snapshot Session::snapshot() const {
    Snapshot s;
    s.state = state_;
    s.fault = fault_;
    s.width = width_;
    s.height = height_;
    s.interlaced = interlaced_;
    s.framesRendered = framesRendered_;
    s.usbSpeed = usbSpeed_;
    s.bytes = bytesBase_;
    std::lock_guard<std::mutex> lk(streamMutex_);
    if (const Stream* st = stream_.get()) {
        const ParserStats& ps = st->parser.stats();
        s.bytes += st->bulk.stats().bytes;
        s.framesParsed = ps.frames;
        s.dropped = ps.dropped;
        s.partial = ps.partial;
        s.resyncs = ps.resyncs;
        s.usbErrors = st->bulk.stats().errors;
        s.lastUsbError = st->bulk.stats().lastError;
    }
    return s;
}

// ---------------------------------------------------------------------------------------------

void Session::controlLoop() {
    frontEnd_ = createFrontEnd(pid_);
    if (!frontEnd_) {
        fail(Fault::UnknownDevice);
        return;
    }
    usbSpeed_ = usb_.speed();
    LOGI("%s, velocidade USB %d (3=high 5=super)", frontEnd_->name(), usbSpeed_.load());

    // Volta a configuração ao estado de fábrica (todas as interfaces em alt 0) antes de reivindicá-las.
    const int cfg = usb_.setConfiguration(1);
    if (cfg < 0) LOGW("SET_CONFIGURATION falhou (%d); seguindo", cfg);
    if (usb_.claimInterface(kVideoInterface) < 0) {
        fail(Fault::UsbSetup);
        return;
    }
    if (usb_.claimInterface(kEventInterface) < 0) LOGW("interface de eventos não reivindicada");
    usb_.setInterface(kVideoInterface, 0);

    // O que a placa realmente oferece nesta velocidade (em USB 2.0 só existem alts isócronos).
    {
        const std::vector<uint8_t> desc = usb_.readDescriptors();
        alts_ = parseAlts(desc.data(), desc.size(), kVideoInterface, kVideoEndpoint, usbSpeed_ >= 5);
        for (const auto& a : alts_) {
            LOGI("alt %u: EP 0x83 %s, %u bytes/intervalo, bInterval %u", a.alt,
                 !a.present ? "ausente" : (a.transferType == 1 ? "isócrono" : (a.transferType == 2 ? "bulk" : "outro")),
                 a.packetBytes, a.interval);
        }
    }

    const int init = frontEnd_->start(usb_);
    if (init == kErrNotImplemented) {
        fail(Fault::FrontEndMissing);
        usb_.releaseInterface(kEventInterface);
        usb_.releaseInterface(kVideoInterface);
        return;
    }
    if (init < 0) {
        fail(usb_.wedged() ? Fault::DeviceLost : Fault::InitFailed);
        usb_.releaseInterface(kEventInterface);
        usb_.releaseInterface(kVideoInterface);
        return;
    }

    state_ = static_cast<int>(SessionState::NoSignal);
    int noSignalPolls = 0;

    while (!quit_) {
        SignalInfo signal;
        const int r = frontEnd_->detect(usb_, &signal);
        if (r < 0) {
            if (usb_.wedged() || r == -ENODEV) {
                fail(Fault::DeviceLost);
                break;
            }
            LOGW("leitura do sinal falhou (%d)", r);
        } else if (signal.present && signal.geometry.valid()) {
            noSignalPolls = 0;
            if (stream_ && !(stream_->g == signal.geometry)) {
                LOGI("fonte mudou para %ux%u; reiniciando o transporte", signal.geometry.width, signal.geometry.height);
                stopStream();
            }
            if (!stream_ && !startStream(signal)) {
                fail(Fault::TransportFailed);
                break;
            }
        } else if (++noSignalPolls >= kNoSignalDebounce) {
            if (stream_) {
                LOGI("sinal HDMI perdido");
                stopStream();
            }
            state_ = static_cast<int>(SessionState::NoSignal);
        }

        if (!waitFor(kPollMs)) break;
    }

    stopStream();
    frontEnd_.reset();
    usb_.releaseInterface(kEventInterface);
    usb_.releaseInterface(kVideoInterface);
    if (state_ != static_cast<int>(SessionState::Error)) state_ = static_cast<int>(SessionState::Idle);
}

bool Session::startStream(const SignalInfo& signal) {
    const double pixelRate = static_cast<double>(signal.geometry.width) * signal.geometry.height * signal.fps;
    const TransportPlan plan = pickTransport(pixelRate, usbSpeed_ >= 5, alts_);
    if (!plan.valid) {
        LOGE("nenhum alt-setting utilizável para %.1f Mpx/s", pixelRate / 1e6);
        return false;
    }
    LOGI("%.1f Mpx/s -> alt %u (%s, %u bytes/intervalo)", pixelRate / 1e6, plan.alt, plan.iso ? "isócrono" : "bulk",
         plan.packetBytes);

    if (frontEnd_->beginStream(usb_) < 0) return false;
    std::this_thread::sleep_for(std::chrono::milliseconds(kGateSettleMs));

    const int alt = usb_.setInterface(kVideoInterface, plan.alt);
    if (alt < 0) {
        LOGE("SET_INTERFACE alt %u falhou (%d)", plan.alt, alt);
        frontEnd_->endStream(usb_);
        return false;
    }

    auto stream = std::make_unique<Stream>(usb_, signal.geometry, defaultColorFor(signal.geometry.height), plan);
    stream->color.bt709 = signal.bt709;
    // Sinal maior do que o transporte comporta (ex.: 1080p60 em USB 2.0): modo de diagnóstico.
    const double capacity = plan.iso ? plan.packetBytes * 8000.0 : 1e12;
    if (pixelRate * 2.0 > capacity) {
        stream->parser.setTolerant(true);
        LOGW("sinal acima da banda do transporte: modo tolerante (imagem incompleta)");
    }
    Stream* raw = stream.get();
    stream->renderer = std::thread([this, raw] { renderLoop(raw); });

    const bool ok = stream->bulk.start([raw](const uint8_t* data, size_t len) {
        // Diagnóstico: os primeiros pacotes em hexadecimal, para conferir o formato do fluxo.
        if (raw->dumps < 6) {
            char hex[3 * 40 + 1];
            const size_t n = std::min<size_t>(len, 40);
            for (size_t i = 0; i < n; i++) std::snprintf(hex + i * 3, 4, "%02x ", data[i]);
            LOGI("pacote %d (%zu bytes): %s", raw->dumps, len, hex);
            raw->dumps++;
        }
        raw->parser.feed(data, len);
    });
    if (!ok) {
        LOGE("anel de URBs não subiu");
        usb_.setInterface(kVideoInterface, 0);
        stream->quit = true;
        stream->pool.wake();
        stream->renderer.join();
        frontEnd_->endStream(usb_);
        return false;
    }

    width_ = signal.geometry.width;
    height_ = signal.geometry.height;
    interlaced_ = signal.geometry.interlaced;
    {
        std::lock_guard<std::mutex> lk(streamMutex_);
        stream_ = std::move(stream);
    }
    state_ = static_cast<int>(SessionState::Streaming);
    LOGI("capturando %ux%u%s a %.2f fps", signal.geometry.width, signal.geometry.height,
         signal.geometry.interlaced ? "i" : "p", signal.fps);
    return true;
}

void Session::stopStream() {
    if (!stream_) return;
    // Na ordem do transporte: alt 0 com os URBs ainda vivos (a FX3 esvazia o canal DMA para um pipe
    // que o host ainda atende) e só então cancela os URBs e desliga o portão de vídeo.
    usb_.setInterface(kVideoInterface, 0);
    stream_->bulk.stop();
    stream_->quit = true;
    stream_->pool.wake();
    if (stream_->renderer.joinable()) stream_->renderer.join();

    bytesBase_ += stream_->bulk.stats().bytes;
    {
        std::lock_guard<std::mutex> lk(streamMutex_);
        stream_.reset();
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(kTeardownSettleMs));
    frontEnd_->endStream(usb_);
}

void Session::renderLoop(Stream* s) {
    while (const uint8_t* src = s->pool.take(s->quit)) {
        {
            std::lock_guard<std::mutex> lk(windowMutex_);
            if (window_) {
                if (s->appliedWindowGeneration != windowGeneration_) {
                    ANativeWindow_setBuffersGeometry(window_, static_cast<int32_t>(s->g.width),
                                                     static_cast<int32_t>(s->g.height), WINDOW_FORMAT_RGBA_8888);
                    s->appliedWindowGeneration = windowGeneration_;
                }
                ANativeWindow_Buffer buf;
                if (ANativeWindow_lock(window_, &buf, nullptr) == 0) {
                    if (buf.format == WINDOW_FORMAT_RGBA_8888 || buf.format == WINDOW_FORMAT_RGBX_8888) {
                        const uint32_t w = std::min<uint32_t>(s->g.width, static_cast<uint32_t>(buf.width)) & ~1u;
                        const uint32_t h = std::min<uint32_t>(s->g.height, static_cast<uint32_t>(buf.height));
                        yuy2ToRgba(src, s->g.lineBytes(), static_cast<uint8_t*>(buf.bits),
                                   static_cast<size_t>(buf.stride) * 4, w, h, s->color);
                        framesRendered_++;
                    }
                    ANativeWindow_unlockAndPost(window_);
                }
            }
        }
        s->pool.release();
    }
}

}  // namespace hd60s
