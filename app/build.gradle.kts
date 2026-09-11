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

    // Definir valores base
    val baseVersionCode = 1 // Código base (ex: 1)
    val baseVersionName = "1.0.0" // Nome base (ex: "1.0.0")

    // Função auxiliar para incrementar o patch (último número) de uma versão semântica
    fun incrementVersionPatch(version: String): String {
        val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
        if (parts.size >= 3) {
            val incrementedParts = parts.toMutableList()
            incrementedParts[2] = incrementedParts[2] + 1 // Incrementa o PATCH
            return incrementedParts.joinToString(".")
        }
        return version // Retorna a original se não for possível parsear
    }

    val releaseVersionName = incrementVersionPatch(baseVersionName)
    val debugVersionName = baseVersionName // O sufixo sera adicionado via versionNameSuffix

 
    val finalVersionNameForDefaultConfig = releaseVersionName // Assume que o default (release) usa a versao incrementada
    
    val forceReleaseVersion = project.findProperty("forceReleaseVersion") as String?
    val calculatedVersionName = if (forceReleaseVersion != null) {
        forceReleaseVersion // Use a versão forçada se a propriedade estiver definida
    } else {
        baseVersionName // Use a base para debug ou se não for forçado
    }

    defaultConfig {
        applicationId = "com.bragastudio.mobile"
        minSdk = 26
        targetSdk = 34

        versionCode = baseVersionCode
        versionName = calculatedVersionName

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