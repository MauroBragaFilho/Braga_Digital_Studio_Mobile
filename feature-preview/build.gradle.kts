plugins {
    id("bdsm.android.library")
    id("bdsm.android.compose")
    id("bdsm.android.hilt")
    id("bdsm.android.test")
}

android {
    namespace = "com.bragastudio.mobile.featurepreview"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(project(":core-media"))
    implementation(project(":core-capture"))
    // PreviewViewModel/HudLogic usam LinkTelemetry e TallyState (core-network). SonyCameraStatus vive em :core.
    implementation(project(":core-network"))
    implementation(project(":common"))
    implementation(project(":core"))
}
