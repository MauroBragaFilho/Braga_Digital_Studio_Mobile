// "Ver na rede": descoberta de fontes NDI (NDIlib_find) e recebimento para a prévia (NDIlib_recv).
// Independente do sender (NdiEngine.cpp). A prévia é só vídeo: os quadros são convertidos para
// RGBX pelo SDK e copiados direto para o buffer da Surface (ANativeWindow), sem GL e sem Bitmap.
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "ndi/Include/Processing.NDI.Lib.h"

#define NET_TAG "BDSM_NDI_NET"
#define NLOGI(...) __android_log_print(ANDROID_LOG_INFO, NET_TAG, __VA_ARGS__)
#define NLOGE(...) __android_log_print(ANDROID_LOG_ERROR, NET_TAG, __VA_ARGS__)

namespace {

constexpr int kMaxSources = 64;
// Teto de exibição da prévia (decisão do produto: 720p).
constexpr int kMaxOutW = 1280;
constexpr int kMaxOutH = 720;
constexpr int kNoSignalMs = 3000;
constexpr int kConnectingGraceMs = 8000;

enum RecvStatus { STATUS_CONNECTING = 0, STATUS_LIVE = 1, STATUS_NO_SIGNAL = 2, STATUS_ERROR = 3 };

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// ----- descoberta -----
std::mutex gFindMutex;
NDIlib_find_instance_t gFind = nullptr;

// ----- recebimento -----
struct RecvCtx {
    NDIlib_recv_instance_t recv = nullptr;
    std::thread thread;
    std::atomic<bool> running{false};
    std::atomic<int> status{STATUS_CONNECTING};
    std::atomic<int> srcW{0};
    std::atomic<int> srcH{0};
    std::atomic<int> outW{0};
    std::atomic<int> outH{0};
    std::atomic<int> fps10{0};
    int64_t startMs = 0;
};

std::mutex gRecvMutex;  // ciclo de vida (start/stop/state)
RecvCtx* gRecv = nullptr;

std::mutex gWinMutex;   // a janela é usada pela thread de captura e trocada pelo JNI
ANativeWindow* gWindow = nullptr;
int gGeomW = 0;
int gGeomH = 0;

void computeOutputSize(int w, int h, int* ow, int* oh) {
    if (w <= kMaxOutW && h <= kMaxOutH) {
        *ow = w;
        *oh = h;
        return;
    }
    const double s = std::min(static_cast<double>(kMaxOutW) / w, static_cast<double>(kMaxOutH) / h);
    *ow = std::max(2, static_cast<int>(w * s) & ~1);
    *oh = std::max(2, static_cast<int>(h * s) & ~1);
}

// Copia o quadro (RGBX/RGBA) para a Surface, reduzindo (vizinho mais próximo) quando passa de 720p.
void renderFrame(const NDIlib_video_frame_v2_t& v, RecvCtx* ctx) {
    if (!v.p_data || v.xres <= 0 || v.yres <= 0) return;
    if (v.FourCC != NDIlib_FourCC_video_type_RGBX && v.FourCC != NDIlib_FourCC_video_type_RGBA &&
        v.FourCC != NDIlib_FourCC_video_type_BGRX && v.FourCC != NDIlib_FourCC_video_type_BGRA) {
        return;
    }
    int ow = 0, oh = 0;
    computeOutputSize(v.xres, v.yres, &ow, &oh);
    ctx->srcW.store(v.xres);
    ctx->srcH.store(v.yres);
    ctx->outW.store(ow);
    ctx->outH.store(oh);

    std::lock_guard<std::mutex> lock(gWinMutex);
    if (!gWindow) return;
    if (gGeomW != ow || gGeomH != oh) {
        if (ANativeWindow_setBuffersGeometry(gWindow, ow, oh, WINDOW_FORMAT_RGBX_8888) != 0) {
            NLOGE("setBuffersGeometry falhou (%dx%d)", ow, oh);
            return;
        }
        gGeomW = ow;
        gGeomH = oh;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(gWindow, &buf, nullptr) != 0) return;
    if (buf.format != WINDOW_FORMAT_RGBX_8888 && buf.format != WINDOW_FORMAT_RGBA_8888) {
        ANativeWindow_unlockAndPost(gWindow);
        return;
    }
    const int srcStride = v.line_stride_in_bytes > 0 ? v.line_stride_in_bytes : v.xres * 4;
    const int copyH = std::min(oh, buf.height);
    const int copyW = std::min(ow, buf.width);
    uint8_t* dst = static_cast<uint8_t*>(buf.bits);
    const size_t dstStride = static_cast<size_t>(buf.stride) * 4;
    const bool swapRb = (v.FourCC == NDIlib_FourCC_video_type_BGRX || v.FourCC == NDIlib_FourCC_video_type_BGRA);
    if (ow == v.xres && oh == v.yres && !swapRb) {
        for (int y = 0; y < copyH; ++y) {
            std::memcpy(dst + y * dstStride, v.p_data + static_cast<size_t>(y) * srcStride, static_cast<size_t>(copyW) * 4);
        }
    } else {
        static thread_local std::vector<int> xmap;
        xmap.resize(copyW);
        for (int x = 0; x < copyW; ++x) xmap[x] = static_cast<int>(static_cast<int64_t>(x) * v.xres / ow);
        for (int y = 0; y < copyH; ++y) {
            const int sy = static_cast<int>(static_cast<int64_t>(y) * v.yres / oh);
            const uint32_t* srcRow = reinterpret_cast<const uint32_t*>(v.p_data + static_cast<size_t>(sy) * srcStride);
            uint32_t* dstRow = reinterpret_cast<uint32_t*>(dst + y * dstStride);
            if (swapRb) {
                for (int x = 0; x < copyW; ++x) {
                    const uint32_t p = srcRow[xmap[x]];
                    dstRow[x] = (p & 0xFF00FF00u) | ((p & 0xFFu) << 16) | ((p >> 16) & 0xFFu);
                }
            } else {
                for (int x = 0; x < copyW; ++x) dstRow[x] = srcRow[xmap[x]];
            }
        }
    }
    ANativeWindow_unlockAndPost(gWindow);
}

void captureLoop(RecvCtx* ctx) {
    int64_t lastFrameMs = 0;
    int64_t fpsWindowStart = nowMs();
    int framesInWindow = 0;
    while (ctx->running.load(std::memory_order_acquire)) {
        NDIlib_video_frame_v2_t video = {};
        const NDIlib_frame_type_e type = NDIlib_recv_capture_v2(ctx->recv, &video, nullptr, nullptr, 200);
        const int64_t now = nowMs();
        if (type == NDIlib_frame_type_video) {
            renderFrame(video, ctx);
            NDIlib_recv_free_video_v2(ctx->recv, &video);
            lastFrameMs = now;
            ctx->status.store(STATUS_LIVE);
            ++framesInWindow;
        } else if (type == NDIlib_frame_type_error) {
            ctx->status.store(STATUS_ERROR);
        } else if (type == NDIlib_frame_type_none) {
            if (lastFrameMs != 0 && now - lastFrameMs > kNoSignalMs) {
                ctx->status.store(STATUS_NO_SIGNAL);
            } else if (lastFrameMs == 0 && now - ctx->startMs > kConnectingGraceMs) {
                ctx->status.store(STATUS_NO_SIGNAL);
            }
        }
        if (now - fpsWindowStart >= 1000) {
            ctx->fps10.store(static_cast<int>(framesInWindow * 10000 / (now - fpsWindowStart)));
            framesInWindow = 0;
            fpsWindowStart = now;
        }
    }
}

void stopRecvLocked() {
    if (!gRecv) return;
    gRecv->running.store(false, std::memory_order_release);
    if (gRecv->thread.joinable()) gRecv->thread.join();
    if (gRecv->recv) NDIlib_recv_destroy(gRecv->recv);
    delete gRecv;
    gRecv = nullptr;
}

}  // namespace

extern "C" {

// Retorna true se o buscador foi criado (ou já existia).
JNIEXPORT jboolean JNICALL
Java_com_bragastudio_mobile_coremedia_ndi_NdiNative_nativeFindStart(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gFindMutex);
    if (gFind) return JNI_TRUE;
    if (!NDIlib_initialize()) {
        NLOGE("NDIlib_initialize falhou (find)");
        return JNI_FALSE;
    }
    NDIlib_find_create_t create = {};
    create.show_local_sources = true;
    gFind = NDIlib_find_create_v2(&create);
    if (!gFind) NLOGE("NDIlib_find_create_v2 falhou");
    return gFind ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_ndi_NdiNative_nativeFindStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gFindMutex);
    if (gFind) {
        NDIlib_find_destroy(gFind);
        gFind = nullptr;
    }
}

// Espera mudanças (até timeoutMs) e devolve [nome0, url0, nome1, url1, ...]; null = buscador parado.
JNIEXPORT jobjectArray JNICALL
Java_com_bragastudio_mobile_coremedia_ndi_NdiNative_nativeFindPoll(JNIEnv* env, jobject, jint timeoutMs) {
    std::lock_guard<std::mutex> lock(gFindMutex);
    if (!gFind) return nullptr;
    NDIlib_find_wait_for_sources(gFind, static_cast<uint32_t>(timeoutMs < 0 ? 0 : timeoutMs));
    uint32_t count = 0;
    const NDIlib_source_t* sources = NDIlib_find_get_current_sources(gFind, &count);
    if (count > kMaxSources) count = kMaxSources;
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count * 2), stringClass, nullptr);
    for (uint32_t i = 0; i < count; ++i) {
        const char* name = sources[i].p_ndi_name ? sources[i].p_ndi_name : "";
        const char* url = sources[i].p_url_address ? sources[i].p_url_address : "";
        jstring jn = env->NewStringUTF(name);
        jstring ju = env->NewStringUTF(url);
        env->SetObjectArrayElement(result, static_cast<jsize>(i * 2), jn);
        env->SetObjectArrayElement(result, static_cast<jsize>(i * 2 + 1), ju);
        env->DeleteLocalRef(jn);
        env->DeleteLocalRef(ju);
    }
    return result;
}

// lowBandwidth: true = banda "lowest" (proxy); false = "highest" (a exibição é limitada a 720p).
JNIEXPORT jboolean JNICALL
Java_com_bragastudio_mobile_coremedia_ndi_NdiNative_nativeRecvStart(JNIEnv* env, jobject, jstring name, jstring url, jboolean lowBandwidth) {
    std::lock_guard<std::mutex> lock(gRecvMutex);
    stopRecvLocked();
    if (!name) return JNI_FALSE;
    if (!NDIlib_initialize()) {
        NLOGE("NDIlib_initialize falhou (recv)");
        return JNI_FALSE;
    }
    const char* cname = env->GetStringUTFChars(name, nullptr);
    const char* curl = url ? env->GetStringUTFChars(url, nullptr) : nullptr;
    std::string nameCopy = cname ? cname : "";
    std::string urlCopy = curl ? curl : "";
    if (cname) env->ReleaseStringUTFChars(name, cname);
    if (curl) env->ReleaseStringUTFChars(url, curl);

    NDIlib_recv_create_v3_t create = {};
    create.source_to_connect_to.p_ndi_name = nameCopy.c_str();
    create.source_to_connect_to.p_url_address = urlCopy.empty() ? nullptr : urlCopy.c_str();
    create.color_format = NDIlib_recv_color_format_RGBX_RGBA;
    create.bandwidth = lowBandwidth ? NDIlib_recv_bandwidth_lowest : NDIlib_recv_bandwidth_highest;
    create.allow_video_fields = false;
    create.p_ndi_recv_name = "BDSM Preview";

    NDIlib_recv_instance_t recv = NDIlib_recv_create_v3(&create);
    if (!recv) {
        NLOGE("NDIlib_recv_create_v3 falhou");
        return JNI_FALSE;
    }
    auto* ctx = new RecvCtx();
    ctx->recv = recv;
    ctx->startMs = nowMs();
    ctx->running.store(true);
    {
        std::lock_guard<std::mutex> w(gWinMutex);
        gGeomW = 0;
        gGeomH = 0;
    }
    ctx->thread = std::thread(captureLoop, ctx);
    gRecv = ctx;
    NLOGI("receptor iniciado (banda %s)", lowBandwidth ? "lowest" : "highest");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_ndi_NdiNative_nativeRecvStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gRecvMutex);
    stopRecvLocked();
    NLOGI("receptor parado");
}

// [status, srcW, srcH, outW, outH, fps*10]; null = receptor parado.
JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_ndi_NdiNative_nativeRecvState(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gRecvMutex);
    if (!gRecv) return nullptr;
    jint values[6] = {gRecv->status.load(), gRecv->srcW.load(), gRecv->srcH.load(),
                      gRecv->outW.load(), gRecv->outH.load(), gRecv->fps10.load()};
    jintArray arr = env->NewIntArray(6);
    env->SetIntArrayRegion(arr, 0, 6, values);
    return arr;
}

// Troca a Surface de destino (null solta a atual). Seguro com a thread de captura em andamento.
JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_ndi_NdiNative_nativeRecvSetSurface(JNIEnv* env, jobject, jobject surface) {
    std::lock_guard<std::mutex> lock(gWinMutex);
    if (gWindow) {
        ANativeWindow_release(gWindow);
        gWindow = nullptr;
    }
    gGeomW = 0;
    gGeomH = 0;
    if (surface) gWindow = ANativeWindow_fromSurface(env, surface);
}

}  // extern "C"
