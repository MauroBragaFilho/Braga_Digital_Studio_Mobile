import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Dependencias de teste comuns (JVM e instrumentacao). */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            dependencies {
                add("testImplementation", libs.lib("junit"))
                add("testImplementation", libs.lib("kotlinx-coroutines-test"))
                add("testImplementation", libs.lib("mockk"))
                add("testImplementation", libs.lib("turbine"))
                add("testImplementation", libs.lib("json-org"))
                add("androidTestImplementation", libs.lib("androidx-junit"))
                add("androidTestImplementation", libs.lib("androidx-espresso-core"))
            }
        }
    }
}
