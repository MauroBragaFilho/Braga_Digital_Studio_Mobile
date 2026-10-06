import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** Version catalog "libs" (gradle/libs.versions.toml) do projeto que aplicou o plugin. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<*> =
    findLibrary(alias).orElseThrow { IllegalStateException("Alias '$alias' ausente em libs.versions.toml") }
