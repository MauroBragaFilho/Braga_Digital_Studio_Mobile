plugins {
    id("bdsm.android.library")
    id("bdsm.android.compose")
    id("bdsm.android.hilt")
    id("bdsm.android.test")
}

android {
    namespace = "com.bragastudio.mobile.featuresettings"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.material.icons.extended)

    implementation(project(":common"))
    implementation(project(":core"))
    implementation(project(":core-media"))
    implementation(project(":core-capture"))
    // LutsViewModel/Diagnostics usam tipos de core-network (core-media/core-capture o tem
    // como `implementation`, entao nao e transitivo).
    implementation(project(":core-network"))
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
