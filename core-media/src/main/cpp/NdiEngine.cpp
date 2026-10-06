#include <jni.h>
#include <string>
#include <android/log.h>
#include <atomic>
#include <vector>
#include <mutex>
#include <shared_mutex>
#include <cstdint>
#include "ndi/Include/Processing.NDI.Lib.h"
#include "ndi/Include/Processing.NDI.utilities.h"

#define LOG_TAG "BDSM_NDI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static NDIlib_send_instance_t pNDI_send = nullptr;

// Vídeo e áudio do NDI são enviados de threads diferentes e o SDK permite
// chamadas concorrentes de send_video/send_audio no mesmo sender. Por isso o
// ciclo de vida (init/stop) é exclusivo e os envios compartilham (leitura) —
// antes um único mutex fazia o áudio esperar o send de vídeo clockado.
static std::shared_mutex ndiMutex;

// Buffer de conversão do áudio (planar float). Só a thread de áudio o usa;
// o mutex próprio evita corrida se houver mais de um chamador, sem tocar no vídeo.
static std::mutex audioBufferMutex;
static std::vector<float> audioFloatBuffer;

// Taxa de quadros anunciada no vídeo NDI (padrão 30000/1000).
static std::atomic<int> ndiFrameRateN{30000};
static std::atomic<int> ndiFrameRateD{1000};

// Define a pasta de configuração do NDI (ndi-config.v1.json) ANTES do NDIlib_initialize:
// é daí que o SDK lê o nome da máquina exibido na rede ("BDSM (nome)").
extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_domain_NdiManager_nativeSetConfigDir(JNIEnv *env, jobject thiz, jstring dir) {
    if (dir == nullptr) return;
    const char *path = env->GetStringUTFChars(dir, nullptr);
    if (path != nullptr) {
        setenv("NDI_CONFIG_DIR", path, 1);
        env->ReleaseStringUTFChars(dir, path);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bragastudio_mobile_coremedia_domain_NdiManager_initNDI(JNIEnv *env, jobject thiz, jstring name) {
    std::unique_lock<std::shared_mutex> lock(ndiMutex);

    if (pNDI_send) return JNI_TRUE; // já iniciado

    if (!NDIlib_initialize()) {
        LOGE("Cannot run NDI");
        return JNI_FALSE;
    }

    const char *nativeName = name ? env->GetStringUTFChars(name, nullptr) : nullptr;

    NDIlib_send_create_t NDI_send_create_desc = {};
    NDI_send_create_desc.p_ndi_name = nativeName ? nativeName : "BDSM - CAM";
    NDI_send_create_desc.p_groups = nullptr;
    NDI_send_create_desc.clock_video = true;
    NDI_send_create_desc.clock_audio = true;

    pNDI_send = NDIlib_send_create(&NDI_send_create_desc);

    if (nativeName) env->ReleaseStringUTFChars(name, nativeName);

    if (!pNDI_send) {
        LOGE("Failed to create NDI sender");
        NDIlib_destroy();
        return JNI_FALSE;
    }

    LOGI("NDI Sender created successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_domain_NdiManager_sendFrameRgba(
    JNIEnv *env, jobject thiz,
    jobject rgbaBuffer,
    jint width, jint height, jint rowStride) {

    std::shared_lock<std::shared_mutex> lock(ndiMutex);
    if (!pNDI_send || !rgbaBuffer || width <= 0 || height <= 0 || rowStride < width * 4) return;

    uint8_t *rgbaData = static_cast<uint8_t*>(env->GetDirectBufferAddress(rgbaBuffer));
    if (!rgbaData) return;

    // Sem memcpy: NDIlib_send_send_video_v2 é SÍNCRONO (o SDK copia/consome o
    // quadro antes de retornar), então o buffer do Image pode ser enviado direto;
    // o chamador só fecha o Image depois que esta função volta.
    NDIlib_video_frame_v2_t NDI_video_frame = {};
    NDI_video_frame.xres = width;
    NDI_video_frame.yres = height;
    NDI_video_frame.FourCC = NDIlib_FourCC_type_RGBA;
    NDI_video_frame.p_data = rgbaData;
    NDI_video_frame.line_stride_in_bytes = rowStride;
    NDI_video_frame.frame_rate_N = ndiFrameRateN.load(std::memory_order_relaxed);
    NDI_video_frame.frame_rate_D = ndiFrameRateD.load(std::memory_order_relaxed);
    NDI_video_frame.picture_aspect_ratio = static_cast<float>(width) / static_cast<float>(height);
    NDI_video_frame.frame_format_type = NDIlib_frame_format_type_progressive;
    NDI_video_frame.timecode = NDIlib_send_timecode_synthesize;

    NDIlib_send_send_video_v2(pNDI_send, &NDI_video_frame);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_domain_NdiManager_sendAudioFrame(
    JNIEnv *env, jobject thiz,
    jbyteArray pcmData, jint numSamples, jint numChannels, jint sampleRate) {

    if (!pcmData || numSamples <= 0 || numChannels <= 0 || numChannels > 8) return;

    // PCM16 intercalado: precisa de numSamples*numChannels*2 bytes.
    const jlong neededBytes = static_cast<jlong>(numSamples) * numChannels * 2;
    if (env->GetArrayLength(pcmData) < neededBytes) return;

    std::shared_lock<std::shared_mutex> lock(ndiMutex);
    if (!pNDI_send) return;

    jbyte* pcm = env->GetByteArrayElements(pcmData, nullptr);
    if (!pcm) return;

    {
        std::lock_guard<std::mutex> bufLock(audioBufferMutex);

        const int16_t* pcm16 = reinterpret_cast<const int16_t*>(pcm);
        const size_t totalSamples = static_cast<size_t>(numSamples) * numChannels;
        if (audioFloatBuffer.size() < totalSamples) {
            audioFloatBuffer.resize(totalSamples);
        }

        // De-intercala para o formato planar do NDI (um bloco por canal).
        for (int ch = 0; ch < numChannels; ++ch) {
            float* dst = audioFloatBuffer.data() + static_cast<size_t>(ch) * numSamples;
            for (int i = 0; i < numSamples; ++i) {
                dst[i] = pcm16[static_cast<size_t>(i) * numChannels + ch] / 32768.0f;
            }
        }

        NDIlib_audio_frame_v2_t NDI_audio_frame = {};
        NDI_audio_frame.sample_rate = sampleRate;
        NDI_audio_frame.no_channels = numChannels;
        NDI_audio_frame.no_samples = numSamples;
        NDI_audio_frame.timecode = NDIlib_send_timecode_synthesize;
        NDI_audio_frame.p_data = audioFloatBuffer.data();
        NDI_audio_frame.channel_stride_in_bytes = numSamples * static_cast<int>(sizeof(float));

        NDIlib_send_send_audio_v2(pNDI_send, &NDI_audio_frame);
    }

    env->ReleaseByteArrayElements(pcmData, pcm, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_domain_NdiManager_stopNDI(JNIEnv *env, jobject thiz) {
    std::unique_lock<std::shared_mutex> lock(ndiMutex);
    if (pNDI_send) {
        NDIlib_send_destroy(pNDI_send);
        pNDI_send = nullptr;
        NDIlib_destroy();
    }
}

// Conexões reais (quantos receptores/monitores NDI estão de fato conectados
// a este sender agora) — o SDK já expõe isso via NDIlib_send_get_no_connections.
// timeout 0 = consulta não-bloqueante. Lock compartilhado: não espera mais o
// send de vídeo clockado. NÃO chamar da thread GL (o resultado vai para o
// motor GL via NativeRenderer.setNdiHasReceivers).
extern "C" JNIEXPORT jint JNICALL
Java_com_bragastudio_mobile_coremedia_domain_NdiManager_getConnectionCount(JNIEnv *env, jobject thiz) {
    std::shared_lock<std::shared_mutex> lock(ndiMutex);
    if (!pNDI_send) return 0;
    return static_cast<jint>(NDIlib_send_get_no_connections(pNDI_send, 0));
}

// Taxa de quadros do vídeo NDI (ex.: 30000/1000, 30000/1001, 60000/1000).
extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetNdiFrameRate(JNIEnv *env, jobject thiz, jint numerator, jint denominator) {
    if (numerator <= 0 || denominator <= 0) return;
    ndiFrameRateN.store(numerator, std::memory_order_relaxed);
    ndiFrameRateD.store(denominator, std::memory_order_relaxed);
}
