plugins {
    `kotlin-dsl`
}

group = "com.bragastudio.mobile.buildlogic"

dependencies {
    // compileOnly: as versoes reais vem do classpath do build principal
    // (plugins { alias(libs.plugins.*) apply false } no build.gradle.kts da raiz).
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "bdsm.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "bdsm.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "bdsm.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "bdsm.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidTest") {
            id = "bdsm.android.test"
            implementationClass = "AndroidTestConventionPlugin"
        }
    }
}
