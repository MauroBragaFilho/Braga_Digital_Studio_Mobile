#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include "ndi/Include/Processing.NDI.Lib.h"
#include "ndi/Include/Processing.NDI.utilities.h"

#define LOG_TAG "BSM_NDI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static NDIlib_send_instance_t pNDI_send = nullptr;
static std::vector<float> audioFloatBuffer;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bragastudio_mobile_coremedia_domain_NdiManager_initNDI(JNIEnv *env, jobject thiz, jstring name) {
    if (!NDIlib_initialize()) {
        LOGE("Cannot run NDI");
        return JNI_FALSE;
    }

    const char *nativeName = env->GetStringUTFChars(name, 0);

    NDIlib_send_create_t NDI_send_create_desc = {};
    NDI_send_create_desc.p_ndi_name = nativeName;
    NDI_send_create_desc.p_groups = nullptr;
    NDI_send_create_desc.clock_video = true;
    NDI_send_create_desc.clock_audio = true;

    pNDI_send = NDIlib_send_create(&NDI_send_create_desc);
    
    env->ReleaseStringUTFChars(name, nativeName);

    if (!pNDI_send) {
        LOGE("Failed to create NDI sender");
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
    
    if (!pNDI_send) return;

    uint8_t *rgbaData = (uint8_t *)env->GetDirectBufferAddress(rgbaBuffer);
    if (!rgbaData) return;

    NDIlib_video_frame_v2_t NDI_video_frame = {};
    NDI_video_frame.xres = width;
    NDI_video_frame.yres = height;
    NDI_video_frame.FourCC = NDIlib_FourCC_type_RGBA;
    NDI_video_frame.p_data = rgbaData;
    NDI_video_frame.line_stride_in_bytes = rowStride;
    NDI_video_frame.frame_rate_N = 30000;
    NDI_video_frame.frame_rate_D = 1000;
    NDI_video_frame.picture_aspect_ratio = 16.0f / 9.0f;
    NDI_video_frame.frame_format_type = NDIlib_frame_format_type_progressive;
    NDI_video_frame.timecode = NDIlib_send_timecode_synthesize;

    NDIlib_send_send_video_v2(pNDI_send, &NDI_video_frame);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_domain_NdiManager_sendAudioFrame(
    JNIEnv *env, jobject thiz,
    jbyteArray pcmData, jint numSamples, jint numChannels, jint sampleRate) {
    
    if (!pNDI_send) return;

    jbyte* pcm = env->GetByteArrayElements(pcmData, 0);
    int16_t* pcm16 = reinterpret_cast<int16_t*>(pcm);
    
    size_t totalSamples = numSamples * numChannels;
    if (audioFloatBuffer.size() < totalSamples) {
        audioFloatBuffer.resize(totalSamples);
    }
    
    if (numChannels == 1) {
        for (int i = 0; i < numSamples; ++i) {
            audioFloatBuffer[i] = pcm16[i] / 32768.0f;
        }
    } else if (numChannels == 2) {
        float* ch0 = audioFloatBuffer.data();
        float* ch1 = audioFloatBuffer.data() + numSamples;
        for (int i = 0; i < numSamples; ++i) {
            ch0[i] = pcm16[i * 2] / 32768.0f;
            ch1[i] = pcm16[i * 2 + 1] / 32768.0f;
        }
    }
    
    NDIlib_audio_frame_v2_t NDI_audio_frame = {};
    NDI_audio_frame.sample_rate = sampleRate;
    NDI_audio_frame.no_channels = numChannels;
    NDI_audio_frame.no_samples = numSamples;
    NDI_audio_frame.timecode = NDIlib_send_timecode_synthesize;
    NDI_audio_frame.p_data = audioFloatBuffer.data();
    NDI_audio_frame.channel_stride_in_bytes = numSamples * sizeof(float);
    
    NDIlib_send_send_audio_v2(pNDI_send, &NDI_audio_frame);
    
    env->ReleaseByteArrayElements(pcmData, pcm, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_domain_NdiManager_stopNDI(JNIEnv *env, jobject thiz) {
    if (pNDI_send) {
        NDIlib_send_destroy(pNDI_send);
        pNDI_send = nullptr;
    }
    NDIlib_destroy();
}
