#pragma once

#include <android/native_window.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "Hd60sDescriptors.h"
#include "Hd60sFrontEnd.h"
#include "Hd60sUsb.h"

namespace hd60s {

enum class SessionState : int {
    Idle = 0,
    Initializing = 1,
    NoSignal = 2,
    Streaming = 3,
    Error = 4,
};

/** Por que a sessão entrou em Error (o texto para o usuário é decidido no lado Kotlin). */
enum class Fault : int {
    None = 0,
    UnknownDevice = 1,     // PID que não é de uma HD60 S
    FrontEndMissing = 2,   // inicialização do hardware ainda não implementada
    UsbSetup = 3,          // não conseguiu reivindicar a interface de vídeo
    InitFailed = 4,        // a inicialização a frio falhou
    TransportFailed = 5,   // alt-setting / anel de URBs não subiu
    DeviceLost = 6,        // cabo puxado ou FX3 travada (timeout de controle)
};

struct Snapshot {
    int state = 0;
    int fault = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    bool interlaced = false;
    uint64_t framesRendered = 0;
    uint64_t bytes = 0;
    uint32_t framesParsed = 0;
    uint32_t dropped = 0;
    uint32_t partial = 0;
    uint32_t resyncs = 0;
    uint32_t usbErrors = 0;
    int usbSpeed = 0;
    int lastUsbError = 0;
};

/**
 * Uma captura de uma HD60 S: thread de controle (inicialização, detecção de sinal, liga/desliga do
 * transporte) e, enquanto há sinal, o anel de URBs bulk -> parser -> conversor -> Surface.
 */
class Session {
public:
    Session(int usbFd, uint16_t productId);
    ~Session();

    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    /** Assume uma referência a `window` (pode ser nullptr: captura sem exibir). */
    bool start(ANativeWindow* window);
    void setWindow(ANativeWindow* window);
    void stop();

    Snapshot snapshot() const;

private:
    class FramePool;
    struct Stream;

    void controlLoop();
    bool startStream(const SignalInfo& signal);
    void stopStream();
    void renderLoop(Stream* stream);
    void fail(Fault fault);
    bool waitFor(int ms);  // false se pediram para parar

    UsbDevice usb_;
    uint16_t pid_;
    std::unique_ptr<FrontEnd> frontEnd_;
    std::vector<AltInfo> alts_;

    std::thread thread_;
    std::mutex waitMutex_;
    std::condition_variable waitCv_;
    std::atomic<bool> quit_{false};

    std::mutex windowMutex_;
    ANativeWindow* window_ = nullptr;
    int windowGeneration_ = 0;

    mutable std::mutex streamMutex_;  // protege stream_ entre a thread de controle e snapshot()
    std::unique_ptr<Stream> stream_;

    std::atomic<int> state_{static_cast<int>(SessionState::Idle)};
    std::atomic<int> fault_{static_cast<int>(Fault::None)};
    std::atomic<uint32_t> width_{0};
    std::atomic<uint32_t> height_{0};
    std::atomic<bool> interlaced_{false};
    std::atomic<uint64_t> framesRendered_{0};
    std::atomic<int> usbSpeed_{0};

    // Totais de streams anteriores (o Stream atual é descartado a cada mudança de modo).
    std::atomic<uint64_t> bytesBase_{0};
};

}  // namespace hd60s
