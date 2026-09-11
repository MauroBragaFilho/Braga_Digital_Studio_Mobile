package com.bragastudio.mobile

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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

            val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                hasCameraPermission.value = permissions[android.Manifest.permission.CAMERA] ?: hasCameraPermission.value
                hasAudioPermission.value = permissions[android.Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission.value
            }

            androidx.compose.runtime.LaunchedEffect(Unit) {
                val permissionsToRequest = mutableListOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO
                )
                // Necessária no Android 13+ para o LinkServerService poder exibir a
                // notificação persistente do Foreground Service (sem ela, o service
                // ainda roda, mas a notificação fica oculta e o SO tende a tratá-lo
                // com menos prioridade).
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                if (!hasCameraPermission.value || !hasAudioPermission.value) {
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
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
                            androidx.compose.material3.Text(text = "Permissões de Câmera e Microfone necessárias", color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }
        }
    }
}

