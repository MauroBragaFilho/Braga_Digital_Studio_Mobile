plugins {
    id("bdsm.android.library")
    id("bdsm.android.compose")
    id("bdsm.android.hilt")
    id("bdsm.android.test")
}

android {
    namespace = "com.bragastudio.mobile.coremedia"

    defaultConfig {
        // ABIs nativas compiladas (libbdsm-media.so). Padrao: so dispositivos reais.
        // Emulador x86: ./gradlew assembleDebug -Pbdsm.abis=arm64-v8a,x86_64
        val bdsmAbis = (project.findProperty("bdsm.abis") as String?)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "armeabi-v7a")
        ndk {
            abiFilters += bdsmAbis
        }
        externalNativeBuild {
            cmake {
                // M3: alinhamento de 16 KB (exige NDK r27+; no r28+ ja e o padrao).
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    // NDK fixado (o instalado localmente e usado nos .cxx do projeto). NDK r27 e o
    // minimo que entende ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES. NDK 25.1 nao serve.
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(project(":core-capture"))
    implementation(project(":core"))
    // Ainda necessário: MediaGraph usa LinkTelemetry e LutRepositoryImpl usa LutLibraryService (core-network).
    // O tipo SonyCameraStatus já foi movido para :core (core.model) e não exige mais essa dependência.
    // `implementation` basta: nenhum tipo de core-network aparece na API pública de outro módulo
    // que o consumidor não declare por conta própria (app/feature-preview declaram :core-network).
    implementation(project(":core-network"))
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.sqlite)
}
