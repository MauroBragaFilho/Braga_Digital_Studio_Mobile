plugins {
    id("bdsm.android.library")
    id("bdsm.android.compose")
    id("bdsm.android.hilt")
    id("bdsm.android.test")
}

android {
    namespace = "com.bragastudio.mobile.featurehome"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(project(":common"))
    implementation(project(":core"))
    implementation(project(":core-network"))
    // HomeViewModel lê o estado da sessão de câmera (MediaGraph / CaptureState).
    implementation(project(":core-media"))
    implementation(project(":core-capture"))

    implementation(libs.coil.compose)

    implementation(libs.androidx.documentfile)
}
