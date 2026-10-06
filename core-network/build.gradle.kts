plugins {
    id("bdsm.android.library")
    alias(libs.plugins.kotlin.serialization)
    id("bdsm.android.hilt")
    id("bdsm.android.test")
}

android {
    namespace = "com.bragastudio.mobile.network"
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.partial.content)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.server.test.host)
}
