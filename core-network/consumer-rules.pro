# Consumer R8 rules de :core-network (Ktor server + Netty + kotlinx.serialization).
# Aplicadas ao :app via consumerProguardFiles.

# Netty carrega muita coisa por reflexao (canais, atributos, field updaters).
-keep class io.netty.** { *; }
-keep class io.ktor.server.netty.** { *; }
-keep class io.ktor.server.engine.** { *; }
-keep class io.ktor.server.application.** { *; }

# Ktor usa ServiceLoader / reflexao em engines e plugins
-keepnames class io.ktor.** { *; }

# SLF4J nao tem binding no Android: ignorar
-dontwarn org.slf4j.**
-dontwarn io.netty.**
-dontwarn io.ktor.**

# DTOs do Link (kotlinx.serialization): serializers gerados pelo plugin
-keepclassmembers class com.bragastudio.mobile.network.** {
    *** Companion;
}
-keepclasseswithmembers class com.bragastudio.mobile.network.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.bragastudio.mobile.network.**$$serializer { *; }
