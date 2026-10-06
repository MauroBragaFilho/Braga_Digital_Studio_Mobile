package com.bragastudio.mobile.modules

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bragastudio.mobile.R
import com.bragastudio.mobile.common.module.AllFeaturesEnabled
import com.bragastudio.mobile.common.module.BdsmModule
import com.bragastudio.mobile.common.module.BdsmModuleRegistry
import com.bragastudio.mobile.common.module.BdsmRoutes
import com.bragastudio.mobile.common.module.FeatureFlags
import com.bragastudio.mobile.common.module.ModuleCategory
import com.bragastudio.mobile.common.module.ModulePlacement
import com.bragastudio.mobile.featurepreview.PreviewScreen
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

private const val MONITOR_FADE_MS = 220

/** Monitor (câmera + gravação): ação principal da Home (cartão ABRIR MONITOR). Tela cheia, fora da barra. */
object MonitorModule : BdsmModule {
    override val id = "monitor"
    override val titleRes = R.string.module_monitor
    override val icon: ImageVector get() = Icons.Filled.Videocam
    override val category = ModuleCategory.CAPTURE
    override val order = 10
    override val placements = setOf(ModulePlacement.HOME_PRIMARY)
    override val route = BdsmRoutes.MONITOR

    override fun registerRoutes(builder: NavGraphBuilder, navController: NavController) {
        builder.composable(
            route,
            enterTransition = { fadeIn(tween(MONITOR_FADE_MS)) },
            exitTransition = { fadeOut(tween(MONITOR_FADE_MS)) },
            popEnterTransition = { fadeIn(tween(MONITOR_FADE_MS)) },
            popExitTransition = { fadeOut(tween(MONITOR_FADE_MS)) },
        ) {
            PreviewScreen(
                onNavigateToSettings = { navController.navigate(BdsmRoutes.SETTINGS) { launchSingleTop = true } },
                // Volta para o Início que já está na pilha (não empilha um segundo).
                onNavigateHome = {
                    if (!navController.popBackStack(BdsmRoutes.HOME, false)) {
                        navController.navigate(BdsmRoutes.HOME) {
                            popUpTo(BdsmRoutes.MONITOR) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
    }
}

/**
 * Registro de módulos: junta tudo o que as features contribuíram com `@Provides @IntoSet`.
 * Para incluir um módulo novo basta contribuí-lo; nada neste arquivo muda.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModules {
    @Provides @IntoSet
    fun monitor(): BdsmModule = MonitorModule

    @Provides
    @Singleton
    fun featureFlags(): FeatureFlags = AllFeaturesEnabled

    @Provides
    @Singleton
    fun registry(modules: @JvmSuppressWildcards Set<BdsmModule>, flags: FeatureFlags): BdsmModuleRegistry = BdsmModuleRegistry(modules, flags)
}
