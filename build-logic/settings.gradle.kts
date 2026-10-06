// Included build com os convention plugins (bdsm.android.*). Registrado em
// pluginManagement { includeBuild("build-logic") } no settings.gradle.kts da raiz.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
