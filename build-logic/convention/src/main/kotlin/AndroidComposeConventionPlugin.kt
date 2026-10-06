import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Jetpack Compose: buildFeatures.compose, plugin do compilador Compose (org.jetbrains.kotlin.plugin.compose,
 * versao = a do Kotlin),
 * BOM do catalogo e as dependencias de UI que todos os modulos Compose repetiam.
 * Funciona em application e library.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // Kotlin 2.x: o compilador Compose vem do plugin (substitui kotlinCompilerExtensionVersion).
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> {
                    buildFeatures { compose = true }
                }
            }
            pluginManager.withPlugin("com.android.library") {
                extensions.configure<LibraryExtension> {
                    buildFeatures { compose = true }
                }
            }

            dependencies {
                val bom = platform(libs.lib("androidx-compose-bom").get())
                add("implementation", bom)
                add("implementation", libs.lib("androidx-ui"))
                add("implementation", libs.lib("androidx-ui-graphics"))
                add("implementation", libs.lib("androidx-ui-tooling-preview"))
                add("implementation", libs.lib("androidx-material3"))
                add("androidTestImplementation", bom)
            }
        }
    }

}
