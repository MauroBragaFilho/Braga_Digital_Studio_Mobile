import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Modelo para um novo `feature-xxx`: biblioteca Android + Compose + Hilt + testes e as dependencias
 * que toda tela repete (:common, :core, navegacao, ViewModel/Hilt para Compose).
 *
 * Uso no build.gradle.kts do modulo:
 *   plugins { id("bdsm.android.feature") }
 *   android { namespace = "com.bragastudio.mobile.featurexxx" }
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("bdsm.android.library")
            pluginManager.apply("bdsm.android.compose")
            pluginManager.apply("bdsm.android.hilt")
            pluginManager.apply("bdsm.android.test")

            dependencies {
                add("implementation", project(":common"))
                add("implementation", project(":core"))
                add("implementation", libs.lib("androidx-core-ktx"))
                add("implementation", libs.lib("androidx-material-icons-extended"))
                add("implementation", libs.lib("androidx-navigation-compose"))
                add("implementation", libs.lib("androidx-hilt-navigation-compose"))
                add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
                add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            }
        }
    }
}
