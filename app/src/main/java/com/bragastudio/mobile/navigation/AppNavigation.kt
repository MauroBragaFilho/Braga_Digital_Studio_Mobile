package com.bragastudio.mobile.navigation

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

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") {
            SplashScreen(
                onNavigateToHome = {
                    navController.navigate("home") {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            )
        }
        composable("home") {
            HomeScreen(
                onNavigateToPreview = { navController.navigate("preview") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToLuts = { navController.navigate("luts") },
                onNavigateToNdiSetup = { navController.navigate("ndi_setup") },
                onNavigateToRecordings = { navController.navigate("recording") }
            )
        }
        composable("preview") {
            PreviewScreen(
                onNavigateToSettings = { navController.navigate("settings") }
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