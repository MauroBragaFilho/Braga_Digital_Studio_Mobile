# Consumer ProGuard rules for module :core-capture
# UVCAndroid carrega libuvc/libusb/libUVCCamera via JNI e chama de volta metodos Java.
-keep class com.serenegiant.** { *; }
-keep class com.herohan.uvcapp.** { *; }
-dontwarn com.serenegiant.**
-dontwarn com.herohan.**
