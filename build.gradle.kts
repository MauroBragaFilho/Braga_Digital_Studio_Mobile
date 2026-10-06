// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// ---------------------------------------------------------------------------
// Qualidade estatica: detekt + ktlint em todos os modulos (analise apenas, nao compila).
//   ./gradlew detekt ktlintCheck
// Os baselines (config/detekt/baseline-<modulo>.xml e <modulo>/ktlint-baseline.xml) congelam
// os achados antigos: so codigo NOVO e cobrado. Para regerar:
//   ./gradlew detektBaseline ktlintGenerateBaseline
// ---------------------------------------------------------------------------
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/baseline-${project.name}.xml")
        source.setFrom("src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin")
        // Se o baseline nao puder ser gerado/atualizado, descomente para nao quebrar o CI:
        // ignoreFailures = true
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs").findVersion("ktlint").get().requiredVersion)
        android.set(true)
        ignoreFailures.set(false)
        // Baseline por modulo (gerado por ktlintGenerateBaseline). Se faltar, nao ha baseline.
        baseline.set(file("ktlint-baseline.xml"))
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
        filter {
            exclude { it.file.path.contains("/build/") || it.file.path.contains("\\build\\") }
        }
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(true)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
}
