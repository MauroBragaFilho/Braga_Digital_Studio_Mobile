# Consumer ProGuard rules for module :core-media
# JNI: simbolos Java_com_bragastudio_mobile_coremedia_* exportados por libbdsm-media.so
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.bragastudio.mobile.coremedia.graphics.NativeRenderer { *; }
-keep class com.bragastudio.mobile.coremedia.domain.NdiManager { *; }
