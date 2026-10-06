package com.bragastudio.mobile.featurehome

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bragastudio.mobile.common.module.BdsmModule
import com.bragastudio.mobile.common.module.BdsmRoutes
import com.bragastudio.mobile.common.module.ModuleCategory
import com.bragastudio.mobile.common.module.ModulePlacement
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

private const val HOME_TRANSITION_MS = 220

/** Início: primeira aba da barra inferior. A tela é gerada a partir do registro de módulos. */
object HomeModule : BdsmModule {
    override val id = "home"
    override val titleRes = R.string.module_home
    override val icon: ImageVector get() = Icons.Filled.Home
    override val category = ModuleCategory.CAPTURE
    override val order = 0
    override val placements = setOf(ModulePlacement.BOTTOM_BAR)
    override val route = BdsmRoutes.HOME

    override fun registerRoutes(builder: NavGraphBuilder, navController: NavController) {
        // Home <-> Monitor usa fade; as demais saídas usam só deslocamento (sem alpha em tela cheia).
        builder.composable(
            route,
            enterTransition = { fadeIn(tween(HOME_TRANSITION_MS)) },
            exitTransition = {
                if (targetState.destination.route == BdsmRoutes.MONITOR) {
                    fadeOut(tween(HOME_TRANSITION_MS))
                } else {
                    slideOutHorizontally(tween(HOME_TRANSITION_MS)) { w -> -w / 5 }
                }
            },
            popEnterTransition = {
                if (initialState.destination.route == BdsmRoutes.MONITOR) {
                    fadeIn(tween(HOME_TRANSITION_MS))
                } else {
                    slideInHorizontally(tween(HOME_TRANSITION_MS)) { w -> -w / 5 }
                }
            },
            popExitTransition = { fadeOut(tween(HOME_TRANSITION_MS)) },
        ) {
            HomeScreen()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object HomeFeatureModules {
    @Provides @IntoSet
    fun home(): BdsmModule = HomeModule
}
