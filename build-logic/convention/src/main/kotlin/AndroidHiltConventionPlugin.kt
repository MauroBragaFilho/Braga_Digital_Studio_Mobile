import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt + KSP: plugins e as dependencias hilt-android / hilt-android-compiler. */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // KSP exige o plugin Kotlin ja aplicado (idempotente).
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            dependencies {
                add("implementation", libs.lib("hilt-android"))
                add("ksp", libs.lib("hilt-android-compiler"))
            }
        }
    }
}
