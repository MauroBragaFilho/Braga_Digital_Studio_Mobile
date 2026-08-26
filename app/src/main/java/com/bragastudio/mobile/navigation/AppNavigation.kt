package com.bragastudio.mobile.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bragastudio.mobile.featurehome.HomeScreen
import com.bragastudio.mobile.featurehome.SplashScreen
import com.bragastudio.mobile.featurepreview.PreviewScreen
import com.bragastudio.mobile.featuresettings.DiagnosticsScreen
import com.bragastudio.mobile.featuresettings.LutManagementScreen
import com.bragastudio.mobile.featuresettings.NdiSetupScreen
import com.bragastudio.mobile.featuresettings.RecordingsScreen
import com.bragastudio.mobile.featuresettings.RouletteScreen
import com.bragastudio.mobile.featuresettings.SettingsScreen

// Duração curta de propósito (220ms): em telas de monitor de vídeo, transições
// longas atrapalham o operador que está trocando de tela rapidamente durante
// uma gravação. É rápido o bastante pra não parecer "flat" mas sem atrapalhar.
private const val NAV_TRANSITION_MS = 220

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = "splash",
        // Transições padrão para toda navegação "para frente" (slide da direita +
        // fade, convenção Android/iOS de empilhar uma nova tela).
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth / 4 },
                animationSpec = tween(NAV_TRANSITION_MS)
            ) + fadeIn(animationSpec = tween(NAV_TRANSITION_MS))
        },
        exitTransition = {
            fadeOut(animationSpec = tween(NAV_TRANSITION_MS))
        },
        // Transições de "voltar" (popBackStack) espelhadas — a tela anterior
        // desliza de volta a partir da esquerda, reforçando a noção de retorno.
        popEnterTransition = {
            fadeIn(animationSpec = tween(NAV_TRANSITION_MS))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth / 4 },
                animationSpec = tween(NAV_TRANSITION_MS)
            ) + fadeOut(animationSpec = tween(NAV_TRANSITION_MS))
        }
    ) {
        composable("splash") {
            SplashScreen(
                onNavigateToHome = {
                    navController.navigate("home") {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            )
        }
        composable(
            "home",
            // Home <-> Preview é a transição mais usada no app (o operador entra e
            // sai do monitor o tempo todo). Um fade puro, sem slide, é mais rápido
            // de "ler" e não compete com o preview de câmera que já está se
            // desenhando por baixo.
            enterTransition = { fadeIn(animationSpec = tween(NAV_TRANSITION_MS)) },
            exitTransition = { fadeOut(animationSpec = tween(NAV_TRANSITION_MS)) },
            popEnterTransition = { fadeIn(animationSpec = tween(NAV_TRANSITION_MS)) },
            popExitTransition = { fadeOut(animationSpec = tween(NAV_TRANSITION_MS)) }
        ) {
            HomeScreen(
                onNavigateToPreview = { navController.navigate("preview") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToLuts = { navController.navigate("luts") },
                onNavigateToNdiSetup = { navController.navigate("ndi_setup") },
                onNavigateToRecordings = { navController.navigate("recording") }
            )
        }
        composable(
            "preview",
            enterTransition = { fadeIn(animationSpec = tween(NAV_TRANSITION_MS)) },
            exitTransition = { fadeOut(animationSpec = tween(NAV_TRANSITION_MS)) },
            popEnterTransition = { fadeIn(animationSpec = tween(NAV_TRANSITION_MS)) },
            popExitTransition = { fadeOut(animationSpec = tween(NAV_TRANSITION_MS)) }
        ) {
            PreviewScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                // O botão de casa dentro do preview levava a nada (default {}) —
                // não estava conectado à navegação real. Agora volta para "home",
                // removendo "preview" da pilha de volta (popUpTo inclusive) para
                // não empilhar telas repetidas se o usuário entrar e sair do
                // preview várias vezes.
                onNavigateHome = {
                    navController.navigate("home") {
                        popUpTo("preview") { inclusive = true }
                    }
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateUp = { navController.popBackStack() },
                onNavigateToEasterEgg = { navController.navigate("easter_egg") },
                onNavigateToDiagnostics = { navController.navigate("diagnostics") }
            )
        }
        composable("easter_egg") {
            RouletteScreen(
                onNavigateUp = { navController.popBackStack() }
            )
        }
        composable("diagnostics") {
            DiagnosticsScreen(
                onNavigateUp = { navController.popBackStack() }
            )
        }
        // Rotas das 3 telas secundárias com Destaque Vermelho Studio e Dual Layout
        composable("luts") {
            LutManagementScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("ndi_setup") {
            NdiSetupScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("recording") {
            RecordingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}