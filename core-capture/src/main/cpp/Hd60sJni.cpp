#include <android/native_window_jni.h>
#include <jni.h>

#include <memory>

#include "Hd60sSession.h"

using hd60s::Session;

namespace {

Session* fromHandle(jlong handle) { return reinterpret_cast<Session*>(handle); }

ANativeWindow* windowFrom(JNIEnv* env, jobject surface) {
    return surface ? ANativeWindow_fromSurface(env, surface) : nullptr;  // já devolve uma referência
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_bragastudio_mobile_corecapture_device_Hd60sNative_nativeCreate(
    JNIEnv*, jobject, jint usbFd, jint productId) {
    return reinterpret_cast<jlong>(new Session(usbFd, static_cast<uint16_t>(productId)));
}

JNIEXPORT jboolean JNICALL Java_com_bragastudio_mobile_corecapture_device_Hd60sNative_nativeStart(
    JNIEnv* env, jobject, jlong handle, jobject surface) {
    Session* s = fromHandle(handle);
    return s && s->start(windowFrom(env, surface)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_bragastudio_mobile_corecapture_device_Hd60sNative_nativeSetSurface(
    JNIEnv* env, jobject, jlong handle, jobject surface) {
    if (Session* s = fromHandle(handle)) s->setWindow(windowFrom(env, surface));
}

JNIEXPORT void JNICALL Java_com_bragastudio_mobile_corecapture_device_Hd60sNative_nativeStop(
    JNIEnv*, jobject, jlong handle) {
    if (Session* s = fromHandle(handle)) s->stop();
}

JNIEXPORT void JNICALL Java_com_bragastudio_mobile_corecapture_device_Hd60sNative_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}

/**
 * [state, fault, width, height, interlaced(0/1), framesRendered, bytes, framesParsed, dropped, partial,
 *  resyncs, usbErrors, usbSpeed, lastUsbError]
 */
JNIEXPORT jlongArray JNICALL Java_com_bragastudio_mobile_corecapture_device_Hd60sNative_nativeSnapshot(
    JNIEnv* env, jobject, jlong handle) {
    Session* s = fromHandle(handle);
    if (!s) return nullptr;
    const hd60s::Snapshot snap = s->snapshot();
    const jlong values[14] = {
        snap.state,
        snap.fault,
        snap.width,
        snap.height,
        snap.interlaced ? 1 : 0,
        static_cast<jlong>(snap.framesRendered),
        static_cast<jlong>(snap.bytes),
        snap.framesParsed,
        snap.dropped,
        snap.partial,
        snap.resyncs,
        snap.usbErrors,
        snap.usbSpeed,
        snap.lastUsbError,
    };
    jlongArray out = env->NewLongArray(14);
    if (out) env->SetLongArrayRegion(out, 0, 14, values);
    return out;
}

}  // extern "C"
