package com.bragastudio.mobile.navigation // Ajuste o pacote conforme o seu projeto

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.bragastudio.mobile.featurehome.HomeScreen
import com.bragastudio.mobile.featurehome.RecordingsScreen
import com.bragastudio.mobile.featurepreview.PreviewScreen
import com.bragastudio.mobile.featuresettings.SettingsScreen
import com.bragastudio.mobile.featuresettings.LutsScreen
import com.bragastudio.mobile.featuresettings.NdiSetupScreen
import com.bragastudio.mobile.featuresettings.RouletteScreen


@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToPreview = { navController.navigate("preview") },
                onNavigateToSettings = { navController.navigate("settings") },
                // Adicionando callbacks para as novas telas
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
                onNavigateToEasterEgg = { navController.navigate("easter_egg") }
            )
        }
        composable("easter_egg") {
            RouletteScreen(
                onNavigateUp = { navController.popBackStack() }
            )
        }
        // Adicionando as novas rotas
        composable("luts") {
            LutsScreen(
                onNavigateUp = { navController.popBackStack() },
                onImportLut = { /* Implemente a lógica de importação */ }
            )
        }
        composable("ndi_setup") {
            NdiSetupScreen(
                onNavigateUp = { navController.popBackStack() },
                onStartStopTransmission = { isStarting -> /* Implemente a lógica para iniciar/parar transmissão */ },
                onTestConnection = { /* Implemente a lógica para testar conexão */ }
            )
        }
        composable("recording") {
            RecordingsScreen(
                onNavigateUp = { navController.popBackStack() }
            )
        }
    }
}