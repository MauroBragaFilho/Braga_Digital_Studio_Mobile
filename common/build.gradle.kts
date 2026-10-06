plugins {
    id("bdsm.android.library")
    id("bdsm.android.compose")
    id("bdsm.android.hilt")
    id("bdsm.android.test")
}

android {
    namespace = "com.bragastudio.mobile.common"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.material.icons.extended)
    // api: BdsmModule expõe NavGraphBuilder/NavController aos módulos de feature.
    api(libs.androidx.navigation.compose)
}
