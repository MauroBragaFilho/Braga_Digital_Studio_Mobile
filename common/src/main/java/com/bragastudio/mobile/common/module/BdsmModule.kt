package com.bragastudio.mobile.common.module

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder

/**
 * Onde um módulo aparece na interface. A Home, a barra inferior e o menu "Avançado" de Ajustes são
 * GERADOS a partir dos módulos registrados: incluir um módulo novo não exige editar essas telas.
 */
enum class ModulePlacement {
    /** Aba da barra de navegação inferior (use poucas: 3 a 5). */
    BOTTOM_BAR,

    /** Ação principal grande da Home (o módulo com menor `order` entre os que a pedem). */
    HOME_PRIMARY,

    /** Cartão da grade 2x2 de módulos da Home (UX v3). */
    HOME_CARD,

    /** Entrada na seção "Módulos" de Ajustes (módulos secundários e futuros, ex.: BSP). */
    MORE,

    /** Conteúdo próprio dentro de Ajustes > Avançado (via [BdsmModule.SettingsContent]). */
    SETTINGS_ADVANCED,
}

/** Agrupamento lógico (usado para ordenar/rotular; não muda o comportamento). */
enum class ModuleCategory { CAPTURE, LIBRARY, NETWORK, SYSTEM }

/** Feature flags: um módulo pode ficar invisível sem remover código (testes A/B, hardware, etc.). */
fun interface FeatureFlags {
    fun isEnabled(flag: String): Boolean
}

/** Padrão: tudo ligado. */
object AllFeaturesEnabled : FeatureFlags {
    override fun isEnabled(flag: String): Boolean = true
}

/**
 * Contrato de um módulo/tela do BDSM. Cada `feature-xxx` declara os seus e os contribui com Hilt
 * (`@Provides @IntoSet`); o app só consome o [BdsmModuleRegistry].
 */
interface BdsmModule {
    /** Identificador estável e único (ex.: "ndi"). */
    val id: String

    @get:StringRes val titleRes: Int

    /** Linha curta sob o título nos atalhos da Home (opcional). */
    @get:StringRes val subtitleRes: Int? get() = null

    val icon: ImageVector
    val category: ModuleCategory get() = ModuleCategory.SYSTEM

    /** Ordem crescente dentro de cada posição. */
    val order: Int

    val placements: Set<ModulePlacement>

    /** Rota de navegação do módulo; null quando ele só contribui com conteúdo (ex.: seção em Ajustes). */
    val route: String? get() = null

    /**
     * Rotas que mantêm este módulo "selecionado" na navegação (a rota principal e sub-rotas que
     * continuam dentro dele, ex.: Mídia = Gravações + LUTs). Padrão: só [route].
     */
    val selectionRoutes: Set<String> get() = route?.let(::setOf) ?: emptySet()

    /** Nome da feature flag que controla a visibilidade (null = sempre visível). */
    val featureFlag: String? get() = null

    fun isVisible(flags: FeatureFlags): Boolean = featureFlag?.let(flags::isEnabled) ?: true

    /** Registra as telas do módulo no grafo de navegação (rota principal e sub-rotas). */
    fun registerRoutes(builder: NavGraphBuilder, navController: NavController) {}

    /** Estado em uma frase para o atalho da Home (ex.: "No ar"); null = nada a mostrar. */
    @Composable
    fun statusLabel(): String? = null

    /** Conteúdo exibido em Ajustes > Avançado quando há [ModulePlacement.SETTINGS_ADVANCED]. */
    @Composable
    fun SettingsContent() {}
}

/** Mensagem de erro se dois módulos usarem o mesmo id (falha cedo, na primeira leitura). */
class DuplicateModuleIdException(id: String) : IllegalStateException("Módulo duplicado: '$id'")

/** Lista imutável de módulos, validada (ids únicos) e ordenada. */
class BdsmModuleRegistry(modules: Collection<BdsmModule>, private val flags: FeatureFlags = AllFeaturesEnabled) {
    private val sorted: List<BdsmModule>

    init {
        val seen = HashSet<String>()
        modules.forEach { if (!seen.add(it.id)) throw DuplicateModuleIdException(it.id) }
        sorted = modules.sortedWith(compareBy<BdsmModule> { it.order }.thenBy { it.id })
    }

    /** Módulos visíveis (flags ligadas), em ordem. */
    val visible: List<BdsmModule> get() = sorted.filter { it.isVisible(flags) }

    fun at(placement: ModulePlacement): List<BdsmModule> = visible.filter { placement in it.placements }

    fun byId(id: String): BdsmModule? = visible.firstOrNull { it.id == id }

    fun byRoute(route: String?): BdsmModule? = if (route == null) null else visible.firstOrNull { it.route == route }

    /** Posição da aba da barra de navegação que contém [route] (ou -1 se a rota não pertence a nenhuma aba). */
    fun tabIndexFor(route: String?): Int {
        if (route == null) return -1
        return at(ModulePlacement.BOTTOM_BAR).indexOfFirst { route in it.selectionRoutes }
    }

    /** A barra de navegação aparece nas rotas de nível superior (as que pertencem a alguma aba). */
    fun showsNavigation(route: String?): Boolean = tabIndexFor(route) >= 0

    /** Registra as rotas de todos os módulos visíveis. */
    fun registerAllRoutes(builder: NavGraphBuilder, navController: NavController) {
        visible.forEach { it.registerRoutes(builder, navController) }
    }
}

/** Rota da tela de uma categoria de configurações (`settings/camera`, `settings/about`...). */
fun settingsCategoryRoute(categoryId: String): String = "settings/$categoryId"

/** O que o app oferece às telas dos módulos (navegar e consultar o registro), via CompositionLocal. */
interface ModuleHost {
    val registry: BdsmModuleRegistry

    /** Navega para uma rota (o app trata casos especiais, como pedir permissões antes do Monitor). */
    fun navigate(route: String)
}

/** Rotas dos módulos nativos (strings únicas num só lugar; módulos novos definem as suas). */
object BdsmRoutes {
    const val HOME = "home"
    const val MONITOR = "preview"
    const val RECORDINGS = "recording"
    const val LUTS = "luts"
    const val MEDIA = "media"
    const val NDI = "ndi_setup"
    const val NDI_ADVANCED = "ndi_advanced"
    const val NDI_PREVIEW = "ndi_preview/{name}"
    const val SETTINGS = "settings"
    const val SETTINGS_CATEGORY = "settings/{category}"
    const val DIAGNOSTICS = "diagnostics"
    const val EASTER_EGG = "easter_egg"
    const val LICENSES = "licenses"
    const val ONBOARDING = "onboarding"
}

val LocalModuleHost = compositionLocalOf<ModuleHost> { error("ModuleHost ausente: envolva o conteúdo com CompositionLocalProvider(LocalModuleHost provides ...)") }
