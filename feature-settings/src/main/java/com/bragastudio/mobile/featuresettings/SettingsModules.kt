package com.bragastudio.mobile.featuresettings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.bragastudio.mobile.common.module.BdsmModule
import com.bragastudio.mobile.common.module.BdsmRoutes
import com.bragastudio.mobile.common.module.ModuleCategory
import com.bragastudio.mobile.common.module.ModulePlacement
import com.bragastudio.mobile.common.module.settingsCategoryRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

// Módulos desta feature. Cada um é contribuído ao registro com @IntoSet: nenhuma lista de telas,
// abas, cartões ou itens de "Mais" é escrita à mão na Home, na navegação ou em Mais.
//
// Barra inferior (UX v3): Início | NDI | Mídia | Ajustes (o Monitor abre pelo cartão da Home).

/** Rota do preview NDI de uma fonte (o nome NDI, nunca o IP, vai codificado na rota). */
internal fun ndiPreviewRoute(sourceName: String): String = BdsmRoutes.NDI_PREVIEW.replace("{name}", android.net.Uri.encode(sourceName))

/** NDI: aba da barra inferior e cartão da Home. Tela simples; avançado em sub-rota. */
object NdiModule : BdsmModule {
    override val id = "ndi"
    override val titleRes = R.string.module_ndi
    override val subtitleRes = R.string.module_ndi_sub
    override val icon: ImageVector get() = Icons.Filled.Router
    override val category = ModuleCategory.NETWORK
    override val order = 20
    override val placements = setOf(ModulePlacement.BOTTOM_BAR, ModulePlacement.HOME_CARD)
    override val route = BdsmRoutes.NDI

    override fun registerRoutes(builder: NavGraphBuilder, navController: NavController) {
        builder.composable(route) {
            NdiScreen(
                onOpenAdvanced = { navController.navigate(BdsmRoutes.NDI_ADVANCED) { launchSingleTop = true } },
                onOpenPreview = { name -> navController.navigate(ndiPreviewRoute(name)) { launchSingleTop = true } },
            )
        }
        builder.composable(BdsmRoutes.NDI_ADVANCED) { NdiAdvancedScreen(onNavigateBack = { navController.popBackStack() }) }
        // Preview NDI imersivo em tela cheia (aberto pelo radar da própria tela NDI).
        builder.composable(
            BdsmRoutes.NDI_PREVIEW,
            arguments = listOf(navArgument(NdiPreviewViewModel.ARG_NAME) { type = NavType.StringType }),
        ) {
            NdiPreviewScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = {
                    if (!navController.popBackStack(BdsmRoutes.HOME, false)) {
                        navController.navigate(BdsmRoutes.HOME) { launchSingleTop = true }
                    }
                },
            )
        }
    }

    @Composable
    override fun statusLabel(): String {
        val vm = hiltViewModel<ModuleStatusViewModel>()
        val enabled by vm.ndiEnabled.collectAsStateWithLifecycle()
        return stringResource(if (enabled) R.string.ndi3_card_on else R.string.ndi3_card_off)
    }
}

/** Mídia: aba da barra inferior que agrupa Gravações e LUTs (seletor no topo). */
object MediaModule : BdsmModule {
    override val id = "media"
    override val titleRes = R.string.module_media
    override val subtitleRes = R.string.module_media_sub
    override val icon: ImageVector get() = Icons.Filled.VideoLibrary
    override val category = ModuleCategory.LIBRARY
    override val order = 30
    override val placements = setOf(ModulePlacement.BOTTOM_BAR)
    override val route = BdsmRoutes.MEDIA

    // Gravações e LUTs continuam com as próprias rotas (atalhos da Home): todas mantêm a aba Mídia selecionada.
    override val selectionRoutes = setOf(BdsmRoutes.MEDIA, BdsmRoutes.RECORDINGS, BdsmRoutes.LUTS)

    override fun registerRoutes(builder: NavGraphBuilder, navController: NavController) {
        builder.composable(route) { MediaScreen(initialTab = MediaTab.RECORDINGS) }
    }
}

/** Gravações: cartão da Home (a tela vive dentro de Mídia). */
object RecordingsModule : BdsmModule {
    override val id = "recordings"
    override val titleRes = R.string.module_recordings
    override val subtitleRes = R.string.module_recordings_sub
    override val icon: ImageVector get() = Icons.Filled.Movie
    override val category = ModuleCategory.LIBRARY
    override val order = 31
    override val placements = setOf(ModulePlacement.HOME_CARD)
    override val route = BdsmRoutes.RECORDINGS

    override fun registerRoutes(builder: NavGraphBuilder, navController: NavController) {
        builder.composable(route) { MediaScreen(initialTab = MediaTab.RECORDINGS) }
    }

    @Composable
    override fun statusLabel(): String? {
        val vm = hiltViewModel<ModuleStatusViewModel>()
        val count by vm.recordings.collectAsStateWithLifecycle()
        return count?.let { pluralStringResource(R.plurals.rec_count, it, it) }
    }
}

/** LUTs: cartão da Home (a tela vive dentro de Mídia). */
object LutsModule : BdsmModule {
    override val id = "luts"
    override val titleRes = R.string.module_luts
    override val subtitleRes = R.string.module_luts_sub
    override val icon: ImageVector get() = Icons.Filled.ColorLens
    override val category = ModuleCategory.LIBRARY
    override val order = 32
    override val placements = setOf(ModulePlacement.HOME_CARD)
    override val route = BdsmRoutes.LUTS

    override fun registerRoutes(builder: NavGraphBuilder, navController: NavController) {
        builder.composable(route) { MediaScreen(initialTab = MediaTab.LUTS) }
    }

    @Composable
    override fun statusLabel(): String? {
        val vm = hiltViewModel<ModuleStatusViewModel>()
        val count by vm.luts.collectAsStateWithLifecycle()
        return count?.let { pluralStringResource(R.plurals.luts_count, it, it) }
    }
}

/** BDSM Link (OBS): contribui com uma seção em Configurações > Aplicativo; não tem tela própria. */
object LinkModule : BdsmModule {
    override val id = "link"
    override val titleRes = R.string.link_section_title
    override val icon: ImageVector get() = Icons.Filled.Cast
    override val category = ModuleCategory.NETWORK
    override val order = 40
    override val placements = setOf(ModulePlacement.SETTINGS_ADVANCED)

    @Composable
    override fun SettingsContent() {
        val vm = hiltViewModel<SettingsViewModel>()
        val link by vm.link.collectAsStateWithLifecycle()
        LinkSettingsSection(
            state = link,
            onEnabledChange = vm::setLinkEnabled,
            onRevoke = vm::revokeLinkClient,
            onRevokeAll = vm::revokeAllLinkClients,
        )
    }
}

/** Ajustes: aba da barra inferior e cartão da Home; abre direto a lista de categorias de Configurações. */
object SettingsModule : BdsmModule {
    override val id = "settings"
    override val titleRes = R.string.module_settings
    override val subtitleRes = R.string.module_settings_sub
    override val icon: ImageVector get() = Icons.Filled.Settings
    override val category = ModuleCategory.SYSTEM
    override val order = 90
    override val placements = setOf(ModulePlacement.BOTTOM_BAR, ModulePlacement.HOME_CARD)
    override val route = BdsmRoutes.SETTINGS

    override fun registerRoutes(builder: NavGraphBuilder, navController: NavController) {
        fun go(route: String) = navController.navigate(route) { launchSingleTop = true }
        builder.composable(route) {
            SettingsScreen(
                onNavigateUp = { navController.popBackStack() },
                showBack = false,
                onOpenCategory = { go(settingsCategoryRoute(it.id)) },
            )
        }
        builder.composable(
            BdsmRoutes.SETTINGS_CATEGORY,
            arguments = listOf(navArgument("category") { type = NavType.StringType }),
        ) { entry ->
            val category = SettingsCategory.fromId(entry.arguments?.getString("category")) ?: SettingsCategory.APP
            SettingsCategoryScreen(
                category = category,
                navigation = SettingsNavigation(
                    onUp = { navController.popBackStack() },
                    onEasterEgg = { go(BdsmRoutes.EASTER_EGG) },
                    onDiagnostics = { go(BdsmRoutes.DIAGNOSTICS) },
                    onNdi = { go(BdsmRoutes.NDI) },
                    onNdiAdvanced = { go(BdsmRoutes.NDI_ADVANCED) },
                    onLicenses = { go(BdsmRoutes.LICENSES) },
                    onOnboarding = { go(BdsmRoutes.ONBOARDING) },
                ),
            )
        }
        builder.composable(BdsmRoutes.DIAGNOSTICS) { DiagnosticsScreen(onNavigateUp = { navController.popBackStack() }) }
        builder.composable(BdsmRoutes.LICENSES) { LicensesScreen(onNavigateBack = { navController.popBackStack() }) }
        builder.composable(BdsmRoutes.EASTER_EGG) { RouletteScreen(onNavigateUp = { navController.popBackStack() }) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SettingsFeatureModules {
    @Provides @IntoSet
    fun ndi(): BdsmModule = NdiModule

    @Provides @IntoSet
    fun media(): BdsmModule = MediaModule

    @Provides @IntoSet
    fun recordings(): BdsmModule = RecordingsModule

    @Provides @IntoSet
    fun luts(): BdsmModule = LutsModule

    @Provides @IntoSet
    fun link(): BdsmModule = LinkModule

    @Provides @IntoSet
    fun settings(): BdsmModule = SettingsModule
}
