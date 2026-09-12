package com.bragastudio.mobile

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.ui.theme.BragaStudioMobileTheme
import com.bragastudio.mobile.navigation.AppNavigation
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val hasCameraPermission = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                )
            }
            val hasAudioPermission = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                )
            }

            // Se o usuário negar/permitir parcial, guarda o estado para oferecer
            // "Tentar novamente" e um atalho para as configurações do sistema —
            // sem isso ele ficava preso numa tela preta sem nenhuma saída.
            val showPermissionFallback = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }

            val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                hasCameraPermission.value = permissions[android.Manifest.permission.CAMERA] ?: hasCameraPermission.value
                hasAudioPermission.value = permissions[android.Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission.value
                if (!hasCameraPermission.value || !hasAudioPermission.value) {
                    showPermissionFallback.value = true
                }
            }

            androidx.compose.runtime.LaunchedEffect(Unit) {
                val permissionsToRequest = requiredPermissions()
                // Necessária no Android 13+ para o LinkServerService poder exibir a
                // notificação persistente do Foreground Service (sem ela, o service
                // ainda roda, mas a notificação fica oculta e o SO tende a tratá-lo
                // com menos prioridade).
                if (!hasCameraPermission.value || !hasAudioPermission.value) {
                    showPermissionFallback.value = true
                    permissionLauncher.launch(permissionsToRequest)
                }
            }

            BragaStudioMobileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (hasCameraPermission.value && hasAudioPermission.value) {
                        AppNavigation()
                    } else {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            PermissionFallbackScreen(
                                showFallbackAction = showPermissionFallback.value,
                                onRetry = { permissionLauncher.launch(requiredPermissions()) },
                                onOpenSettings = { openAppSettings(context) }
                            )
                        }
                    }
                }
            }
        }
    }
}
private fun requiredPermissions(): Array<String> {
    val base = arrayListOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    )
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        base.add(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    return base.toTypedArray()
}

private fun openAppSettings(context: android.content.Context) {
    context.startActivity(
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@androidx.compose.runtime.Composable
private fun PermissionFallbackScreen(
    showFallbackAction: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.material3.Text(
            text = "Permissões de Câmera e Microfone necessárias",
            color = androidx.compose.ui.graphics.Color.White
        )
        Spacer(
            modifier = Modifier.height(16.dp)
        )
        androidx.compose.material3.Button(onClick = onRetry) {
            androidx.compose.material3.Text("Tentar novamente")
        }
        if (showFallbackAction) {
            androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                androidx.compose.material3.Text("Abrir Configurações")
            }
        }
    }
}
