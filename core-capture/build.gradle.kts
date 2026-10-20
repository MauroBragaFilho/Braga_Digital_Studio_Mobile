plugins {
    id("bdsm.android.library")
    id("bdsm.android.compose")
    id("bdsm.android.hilt")
    id("bdsm.android.test")
}

android {
    namespace = "com.bragastudio.mobile.corecapture"

    defaultConfig {
        // ABIs nativas compiladas (libbdsm-hd60s.so), as mesmas de :core-media.
        // Emulador x86: ./gradlew assembleDebug -Pbdsm.abis=arm64-v8a,x86_64
        val bdsmAbis = (project.findProperty("bdsm.abis") as String?)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "armeabi-v7a")
        ndk {
            abiFilters += bdsmAbis
        }
        externalNativeBuild {
            cmake {
                // Alinhamento de 16 KB (exige NDK r27+).
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    // Mesmo NDK de :core-media.
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

    implementation(project(":core"))
    implementation(project(":core-network"))

    implementation(libs.uvc.android)
}
