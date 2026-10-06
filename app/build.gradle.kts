import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("bdsm.android.compose")
    id("bdsm.android.hilt")
    id("bdsm.android.test")
}

android {
    namespace = "com.bragastudio.mobile"
    compileSdk = 36

    // ---------------------------------------------------------------------
    // Versionamento
    // ---------------------------------------------------------------------
    // A fonte da verdade é o baseVersionName abaixo: para lançar uma nova versão,
    // incremente-o (ex: "1.0.0" -> "1.0.1"). O versionCode é derivado do nome via
    // semanticVersionCode (major*10000 + minor*100 + patch), então cresce de forma
    // estritamente monotônica a cada release (requisito para lojas/atualização OTA).
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
    // keyPassword) ou de variáveis de ambiente do CI (KEYSTORE_FILE, KEYSTORE_PASSWORD,
    // KEY_ALIAS, KEY_PASSWORD). Sem keystore configurado, o release usa a chave de debug —
    // suficiente para testar, nunca para distribuir (em tag/-PrequireReleaseSigning=true o
    // build falha, ver taskGraph.whenReady abaixo).
    val keystoreProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    fun signingProp(name: String, env: String): String? = keystoreProps.getProperty(name) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val releaseKeystore: File? = (keystoreProps.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE"))
        ?.takeIf { it.isNotBlank() }
        ?.let { path -> File(path).let { if (it.isAbsolute) it else rootProject.file(path) } }
        ?.takeIf { it.exists() }

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

    // -Pbdsm.abis=arm64-v8a (lista separada por vírgula) limita as ABIs do APK (build local mais
    // rápido). Padrão: arm64-v8a e armeabi-v7a.
    val abis = (project.findProperty("bdsm.abis") as String?)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf("arm64-v8a", "armeabi-v7a")

    defaultConfig {
        applicationId = "io.github.maurobragafilho.bdsm"
        minSdk = 26
        targetSdk = 35

        versionCode = semanticVersionCode(releaseVersionName)
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(abis)
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "- Versão de Desenvolvimento"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        xmlReport = true
        htmlReport = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(project(":common"))
    implementation(project(":core"))
    implementation(project(":core-capture"))
    implementation(project(":core-media"))
    implementation(project(":feature-home"))
    implementation(project(":feature-preview"))
    implementation(project(":feature-settings"))
    implementation(project(":core-network"))

    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Gate de assinatura: em tag (CI) ou com -PrequireReleaseSigning=true, assemble/package/bundleRelease
// falham sem keystore em vez de cair silenciosamente na chave de debug.
gradle.taskGraph.whenReady {
    val releaseRequested = hasTask(":app:assembleRelease") ||
        hasTask(":app:packageRelease") ||
        hasTask(":app:bundleRelease")
    val hasRelease = android.signingConfigs.findByName("release") != null
    val required = System.getenv("GITHUB_REF_TYPE") == "tag" ||
        project.findProperty("requireReleaseSigning")?.toString() == "true"
    if (releaseRequested && !hasRelease && required) {
        throw GradleException(
            "Assinatura de release ausente: defina KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS e KEY_PASSWORD " +
                "(ou storeFile/storePassword/keyAlias/keyPassword em local.properties). " +
                "Recusando assinar o release com a chave de debug.",
        )
    }
}
