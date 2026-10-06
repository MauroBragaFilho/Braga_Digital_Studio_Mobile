package com.bragastudio.mobile.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bragastudio.mobile.common.components.BdsmNavItem
import com.bragastudio.mobile.common.components.BdsmNavigationBar
import com.bragastudio.mobile.common.components.BdsmNavigationRail
import com.bragastudio.mobile.common.module.BdsmModuleRegistry
import com.bragastudio.mobile.common.module.BdsmRoutes
import com.bragastudio.mobile.common.module.LocalModuleHost
import com.bragastudio.mobile.common.module.ModuleHost
import com.bragastudio.mobile.common.module.ModulePlacement
import com.bragastudio.mobile.common.ui.theme.BdsmWidthClass
import com.bragastudio.mobile.common.ui.theme.bdsmWidthClass
import com.bragastudio.mobile.core.domain.LandscapeNavSide
import com.bragastudio.mobile.onboarding.OnboardingPrefs
import com.bragastudio.mobile.onboarding.OnboardingScreen
import com.bragastudio.mobile.permissions.rememberMonitorEntry
import com.bragastudio.mobile.permissions.rememberPermissionsState

// Duração curta de propósito (220ms): em telas de monitor de vídeo, transições longas atrapalham o
// operador que está trocando de tela rapidamente durante uma gravação.
private const val NAV_TRANSITION_MS = 220

/**
 * Navegação gerada a partir do registro de módulos: as abas da barra inferior, as rotas e os atalhos
 * da Home vêm dos [com.bragastudio.mobile.common.module.BdsmModule] registrados (Hilt `@IntoSet`).
 * Aqui ficam só as rotas de infraestrutura do app (boas-vindas/permissões).
 */
@Composable
fun AppNavigation(
    registry: BdsmModuleRegistry,
    railSide: LandscapeNavSide = LandscapeNavSide.Default,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    // Primeiro uso: guia de boas-vindas (leitura síncrona barata de SharedPreferences, sem flash de tela).
    val startDestination = remember { if (OnboardingPrefs.isDone(context)) BdsmRoutes.HOME else BdsmRoutes.ONBOARDING }
    val permissions = rememberPermissionsState()
    // Câmera/microfone são explicados e pedidos ao abrir o Monitor (não mais no 1º quadro do app).
    val openMonitor = rememberMonitorEntry(permissions) { navController.safeNavigate(BdsmRoutes.MONITOR) }

    val tabs = remember(registry) { registry.at(ModulePlacement.BOTTOM_BAR) }
    val tabRoutes = remember(tabs) { tabs.mapNotNull { it.route } }

    val host = remember(registry, navController, openMonitor, tabRoutes) {
        object : ModuleHost {
            override val registry = registry
            override fun navigate(route: String) {
                when (route) {
                    BdsmRoutes.MONITOR -> openMonitor()
                    in tabRoutes -> navController.navigateTab(route, home = tabRoutes.first())
                    else -> navController.safeNavigate(route)
                }
            }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // A navegação (barra inferior ou lateral) só aparece nas telas de nível superior; o Monitor é tela cheia.
    val showNavigation = registry.showsNavigation(currentRoute)
    val selectedIndex = registry.tabIndexFor(currentRoute).coerceAtLeast(0)
    // Classe de largura (não a orientação): paisagem, tablet e dobráveis abertos usam a barra lateral.
    val useRail = bdsmWidthClass() != BdsmWidthClass.Compact
    val navItems = tabs.map { BdsmNavItem(label = stringResource(it.titleRes), icon = it.icon) }
    val onSelectTab: (Int) -> Unit = { index -> tabs[index].route?.let(host::navigate) }

    val railOnEnd = railSide == LandscapeNavSide.END
    // O rail ocupa o lado escolhido (start/end respeitam RTL) e absorve os insets desse lado
    // (cutout + barra de gestos/navegação); o conteúdo do outro lado trata os dele.
    val railInsets = WindowInsets.safeDrawing.only(
        WindowInsetsSides.Vertical + if (railOnEnd) WindowInsetsSides.End else WindowInsetsSides.Start,
    )
    val rail: @Composable () -> Unit = {
        BdsmNavigationRail(items = navItems, selectedIndex = selectedIndex, onSelect = onSelectTab, windowInsets = railInsets)
    }

    CompositionLocalProvider(LocalModuleHost provides host) {
        Scaffold(
            // Transparente: cada tela já desenha o próprio fundo (uma camada de overdraw a menos).
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showNavigation && !useRail) {
                    BdsmNavigationBar(items = navItems, selectedIndex = selectedIndex, onSelect = onSelectTab)
                }
            },
        ) { padding ->
            Row(
                modifier = Modifier
                    .padding(bottom = padding.calculateBottomPadding())
                    .consumeWindowInsets(padding),
            ) {
                if (showNavigation && useRail && !railOnEnd) rail()
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.weight(1f).then(if (showNavigation && useRail) Modifier.consumeWindowInsets(railInsets) else Modifier),
                    // Transições "para frente"/"voltar" só com deslocamento (sem alpha): fade em tela inteira
                    // força camada offscreen e custava 12-18 ms de GPU por quadro no A51 (ARQUITETURA 8.2).
                    enterTransition = { slideInHorizontally(tween(NAV_TRANSITION_MS)) { fullWidth -> fullWidth } },
                    exitTransition = { slideOutHorizontally(tween(NAV_TRANSITION_MS)) { fullWidth -> -fullWidth / 5 } },
                    popEnterTransition = { slideInHorizontally(tween(NAV_TRANSITION_MS)) { fullWidth -> -fullWidth / 5 } },
                    popExitTransition = { slideOutHorizontally(tween(NAV_TRANSITION_MS)) { fullWidth -> fullWidth } },
                ) {
                    // Todas as telas dos módulos (Início, Monitor, NDI, Mídia, Mais, Configurações...).
                    registry.registerAllRoutes(this, navController)

                    composable(BdsmRoutes.ONBOARDING) {
                        OnboardingScreen(
                            permissions = permissions,
                            onFinished = {
                                OnboardingPrefs.markDone(context)
                                // Aberto pelos Ajustes: só volta. No 1º uso: segue para o Início sem empilhar o guia.
                                if (!navController.popBackStack(BdsmRoutes.HOME, false)) {
                                    navController.navigate(BdsmRoutes.HOME) {
                                        popUpTo(BdsmRoutes.ONBOARDING) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            },
                        )
                    }
                }
                if (showNavigation && useRail && railOnEnd) rail()
            }
        }
    }
}

/** Navega sem empilhar a mesma tela duas vezes (duplo toque). */
private fun NavHostController.safeNavigate(route: String) {
    navigate(route) { launchSingleTop = true }
}

/** Troca de aba: guarda/restaura o estado de cada aba e mantém a pilha curta (volta sempre ao Início). */
private fun NavHostController.navigateTab(route: String, home: String) {
    if (route == home) {
        if (!popBackStack(home, false)) navigate(home) { launchSingleTop = true }
        return
    }
    navigate(route) {
        popUpTo(home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
