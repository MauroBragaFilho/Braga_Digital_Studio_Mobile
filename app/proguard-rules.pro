# =====================================================================
# Regras R8/ProGuard do :app (release). M2.
# As regras especificas de cada biblioteca ficam nos consumer-rules.pro dos modulos
# (core-network: Netty/Ktor/serialization; core-media: JNI; core-capture: UVC).
# Se uma build release falhar com "Missing classes", consulte
# app/build/outputs/mapping/release/missing_rules.txt e adicione -dontwarn aqui.
# =====================================================================

# Stack traces legiveis (mapping.txt e arquivado no CI)
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# ---- JNI (libbdsm-media.so) -----------------------------------------
# O C++ exporta Java_com_bragastudio_mobile_coremedia_{graphics_NativeRenderer,domain_NdiManager}_*
# Os nomes de classe e de metodo nativo NAO podem ser ofuscados.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.bragastudio.mobile.coremedia.graphics.NativeRenderer { *; }
-keep class com.bragastudio.mobile.coremedia.domain.NdiManager { *; }

# ---- Hilt / Dagger / AndroidX ViewModel -----------------------------
# (o plugin do Hilt e o AGP ja geram as regras principais; mantidos por seguranca)
-keep class dagger.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ---- Room ------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- kotlinx.serialization ------------------------------------------
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-dontwarn kotlinx.serialization.**

# ---- Compose ---------------------------------------------------------
-dontwarn androidx.compose.**

# ---- Netty / Ktor (servidor embutido, core-network) -----------------
# Regras principais em core-network/consumer-rules.pro; -dontwarn globais aqui porque
# Netty referencia dezenas de classes opcionais (JDK, log4j, BlockHound, Conscrypt...).
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn reactor.blockhound.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn com.google.protobuf.**
-dontwarn sun.security.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn javax.management.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**

# ---- UVCAndroid (USB camera, JNI proprio) ---------------------------
-keep class com.serenegiant.** { *; }
-keep class com.herohan.uvcapp.** { *; }
-dontwarn com.serenegiant.**
-dontwarn com.herohan.**

# ---- Enums persistidos por name() (DataStore: valueOf) --------------
-keepclassmembers enum com.bragastudio.mobile.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
