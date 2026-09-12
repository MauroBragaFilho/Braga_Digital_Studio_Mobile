import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bragastudio.mobile"
    compileSdk = 34

    // ---------------------------------------------------------------------
    // Versionamento
    // ---------------------------------------------------------------------
    // A fonte da verdade é o baseVersionName abaixo: para lançar uma nova versão,
    // incremente-o (ex: "1.0.0" -> "1.0.1"). O versionCode é derivado do nome via
    // semanticVersionCode, então cresce de forma estritamente monotônica a cada
    // release (requisito para lojas/atualização OTA).
    val baseVersionName = "1.0.0"

    // -PforceReleaseVersion=1.2.3 sobrescreve o nome da versão (útil no CI).
    val forceReleaseVersion = project.findProperty("forceReleaseVersion") as String?
    val releaseVersionName = forceReleaseVersion ?: baseVersionName

    fun semanticVersionCode(version: String): Int {
        val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major * 10000 + minor * 100 + patch
    }

    // ---------------------------------------------------------------------
    // Assinatura de release
    // ---------------------------------------------------------------------
    // O keystore pode vir de local.properties (storeFile/storePassword/keyAlias/
    // keyPassword) ou de variáveis de ambiente do CI (KEYSTORE_BASE64,
    // KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD). Sem keystore configurado, o
    // release usa a chave de debug — suficiente para testar, nunca para distribuir.
    val keystoreProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    fun signingProp(name: String, env: String): String? =
        keystoreProps.getProperty(name) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val keystoreBase64 = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }
    val releaseKeystore: File? = if (keystoreBase64 != null) {
        val target = rootProject.file("keystore/bdsm-release.jks")
        target.parentFile?.mkdirs()
        target.writeBytes(Base64.getDecoder().decode(keystoreBase64))
        target
    } else {
        keystoreProps.getProperty("storeFile")
            ?.let { rootProject.file(it) }
            ?.takeIf { it.exists() }
    }

    val hasReleaseSigning = releaseKeystore != null &&
        !signingProp("storePassword", "KEYSTORE_PASSWORD").isNullOrBlank() &&
        !signingProp("keyAlias", "KEY_ALIAS").isNullOrBlank() &&
        !signingProp("keyPassword", "KEY_PASSWORD").isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingProp("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingProp("keyAlias", "KEY_ALIAS")
                keyPassword = signingProp("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.bragastudio.mobile"
        minSdk = 26
        targetSdk = 34

        versionCode = semanticVersionCode(releaseVersionName)
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "- Versão de Desenvolvimento"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(project(":common"))
    implementation(project(":core"))
    implementation(project(":core-capture"))
    implementation(project(":core-media"))
    implementation(project(":feature-home"))
    implementation(project(":feature-preview"))
    implementation(project(":feature-settings"))
    implementation(project(":core-network"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}