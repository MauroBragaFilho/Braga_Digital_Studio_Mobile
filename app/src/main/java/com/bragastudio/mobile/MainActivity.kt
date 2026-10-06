package com.bragastudio.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.module.BdsmModuleRegistry
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.common.ui.theme.BragaStudioMobileTheme
import com.bragastudio.mobile.core.domain.LandscapeNavSide
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.domain.ThemeMode
import com.bragastudio.mobile.navigation.AppNavigation
import com.bragastudio.mobile.network.LinkServerController
import com.bragastudio.mobile.network.auth.LinkAuthManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var linkAuthManager: LinkAuthManager

    @Inject lateinit var linkServerController: LinkServerController

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var moduleRegistry: BdsmModuleRegistry

    @Volatile private var themeReady = false

    /** Modo imersivo: barras de sistema escondidas, reveladas só por gesto. */
    private fun applyImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash (core-splashscreen): fica na tela só até o tema salvo carregar (leitura
        // assíncrona do DataStore), sem espera fixa e sem bloquear a Main.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !themeReady }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersiveMode()

        lifecycleScope.launch {
            val (mode, dynamic) = runCatching {
                settingsRepository.themeMode.first() to settingsRepository.dynamicColorEnabled.first()
            }.getOrDefault(ThemeMode.SYSTEM to false)
            themeReady = true
            setAppContent(mode, dynamic)
        }
    }

    private fun setAppContent(initialThemeMode: ThemeMode, initialDynamic: Boolean) {
        setContent {
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = initialThemeMode)
            val dynamicColor by settingsRepository.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = initialDynamic)
            val railSide by settingsRepository.landscapeNavSide.collectAsStateWithLifecycle(initialValue = LandscapeNavSide.Default)
            val darkTheme = themeMode.resolveDark(isSystemInDarkTheme())

            BragaStudioMobileTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // Transparente: o fundo já é desenhado pela janela e pelo Scaffold de cada tela
                    // (uma camada tela-cheia a menos de overdraw).
                    color = Color.Transparent,
                ) {
                    // Sem portão de permissões: câmera/microfone/notificações são explicadas no
                    // onboarding e pedidas de forma contextual (ver permissions/MonitorEntry).
                    AppNavigation(moduleRegistry, railSide)
                    LinkPairingHost(linkAuthManager)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // O servidor do Link roda em Foreground Service e só pode ser iniciado com o app
        // visível (Android 12+). Respeita a preferência "Link habilitado".
        linkServerController.startIfEnabled()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Diálogos, teclado e o seletor de permissões fazem as barras reaparecerem.
        if (hasFocus) applyImmersiveMode()
    }
}

/**
 * Pergunta ao operador, no celular, se aceita um cliente do BDSM Link (OBS/navegador).
 * O mesmo código de 4 dígitos aparece no cliente: o operador confere que são o mesmo pedido.
 * Aparece em qualquer tela, independentemente das permissões de câmera/microfone.
 */
@Composable
private fun LinkPairingHost(auth: LinkAuthManager) {
    val pending = auth.pending.collectAsStateWithLifecycle().value
    // Faz pedidos expirados sumirem do diálogo.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            auth.refresh()
        }
    }
    val req = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { auth.deny(req.id) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = BdsmTheme.shapes.container,
        title = { Text(stringResource(R.string.pairing_title)) },
        text = {
            Text(stringResource(R.string.pairing_message, req.clientName, req.remoteAddress, req.code))
        },
        confirmButton = {
            BdsmTextButton(onClick = { auth.approve(req.id) }) { Text(stringResource(R.string.pairing_allow)) }
        },
        dismissButton = {
            BdsmTextButton(onClick = { auth.deny(req.id) }) { Text(stringResource(R.string.pairing_deny)) }
        },
    )
}
