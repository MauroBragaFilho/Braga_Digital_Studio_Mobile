pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Braga Digital Studio Mobile"

include(":app")
include(":common")
include(":core")
include(":core-capture")
include(":core-media")
include(":feature-home")
include(":feature-preview")
include(":feature-settings")
include(":core-network")
