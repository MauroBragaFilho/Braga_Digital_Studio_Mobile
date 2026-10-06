plugins {
    id("bdsm.android.library")
    id("bdsm.android.compose")
    id("bdsm.android.hilt")
    id("bdsm.android.test")
}

android {
    namespace = "com.bragastudio.mobile.corecapture"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(project(":core"))
    implementation(project(":core-network"))

    implementation(libs.uvc.android)
}
