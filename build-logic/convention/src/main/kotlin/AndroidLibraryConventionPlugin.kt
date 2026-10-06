import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Configuracao comum dos modulos library: com.android.library + kotlin-android,
 * compileSdk 35 / minSdk 26, Java/Kotlin 17, regras consumer, runner de instrumentacao,
 * unit tests com isReturnDefaultValues e buildType release sem minify (R8 so no :app).
 * namespace e dependencias especificas ficam no build.gradle.kts de cada modulo.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")

            extensions.configure<LibraryExtension> {
                compileSdk = 36

                defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    consumerProguardFiles("consumer-rules.pro")
                }

                buildTypes {
                    release {
                        isMinifyEnabled = false
                        // Sem minify em library: o R8 roda so no :app. Mantem o arquivo local
                        // de regras apenas quando o modulo o possui.
                        if (project.file("proguard-rules.pro").exists()) {
                            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                        } else {
                            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                        }
                    }
                }

                testOptions {
                    unitTests {
                        // android.jar dos testes JVM so tem stubs: devolve null/0 em vez de lancar
                        isReturnDefaultValues = true
                    }
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            tasks.withType<KotlinCompile>().configureEach {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    // Kotlin 2.2+: anotacao em parametro de construtor (@ApplicationContext val ...) passa a valer
                    // tambem no campo; opt-in explicito ao comportamento futuro (KT-73255).
                    freeCompilerArgs.add("-Xannotation-default-target=param-property")
                }
            }
        }
    }
}
