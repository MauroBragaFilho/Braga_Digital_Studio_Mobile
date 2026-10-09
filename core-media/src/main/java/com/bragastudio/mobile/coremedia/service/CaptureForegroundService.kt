package com.bragastudio.mobile.coremedia.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bragastudio.mobile.coremedia.domain.CaptureSessionPolicy
import com.bragastudio.mobile.coremedia.domain.MediaGraph
import com.bragastudio.mobile.coremedia.domain.SessionConsumers
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Âncora de processo/prioridade da sessão de captura (A3 / item 1.9).
 *
 * CONTRATO
 *  - NÃO é dona de nada: câmera, GL, RecordManager, NdiManager, BspManager e áudio continuam
 *    no [MediaGraph] (@Singleton). O serviço só mantém o processo vivo e com acesso à
 *    câmera/microfone em segundo plano (Android 9+ revoga a câmera de apps em background; o tipo
 *    `camera` do FGS é o que a mantém aberta) e mostra a notificação persistente com a ação "Parar".
 *  - É iniciado por [start] SOMENTE com a Activity visível (início de REC/NDI/BSP): Android 12+
 *    recusa FGS iniciado de background e Android 14+ exige as permissões do tipo. Qualquer recusa
 *    é tratada (retorna false) e o app cai no comportamento antigo (sessão encerra ao sair).
 *  - Quem decide quando descer é o [MediaGraph.foregroundWanted] (contagem de referência dos
 *    consumidores + tolerância): o próprio serviço se encerra quando ele fica false. Nunca se usa
 *    `stopService` de fora, para não correr o risco de parar antes do `startForeground`.
 *  - `START_NOT_STICKY`: se o processo morrer, a sessão morreu junto; não há o que reiniciar.
 */
@AndroidEntryPoint
class CaptureForegroundService : Service() {

    @Inject lateinit var graph: MediaGraph

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watching = false
    private var startedAt = 0L

    companion object {
        private const val TAG = "CaptureFgService"

        // Canal novo e de baixa importância (silencioso). O sufixo permite trocar a importância no futuro.
        private const val CHANNEL_ID = "bdsm_capture_session_v1"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.bragastudio.mobile.coremedia.action.CAPTURE_START"
        const val ACTION_STOP_SESSION = "com.bragastudio.mobile.coremedia.action.CAPTURE_STOP"
        private const val EXTRA_CAMERA = "extra_camera"
        private const val EXTRA_MIC = "extra_mic"

        /** Tempo que o serviço espera, após subir, pelo consumidor que o pediu marcar-se como ativo. */
        private const val STARTUP_GRACE_MS = 4_000L

        /** True enquanto uma instância existe (evita pedir o start de novo à toa). */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Pede o início do serviço. Retorna false (sem lançar) quando o sistema recusa, p. ex.
         * `ForegroundServiceStartNotAllowedException` com o app em background (Android 12+) ou
         * `SecurityException` por falta de permissão do tipo (Android 14+).
         */
        fun start(context: Context, camera: Boolean, microphone: Boolean): Boolean {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CAMERA, camera)
                putExtra(EXTRA_MIC, microphone)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                // A classe da exceção só existe a partir do API 31 (minSdk 26): captura genérica.
                Log.w(TAG, "Sistema recusou iniciar a sessão em foreground: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startedAt = System.currentTimeMillis()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SESSION) {
            // Botão "Parar" da notificação: encerra REC/NDI/BSP; o grafo derruba a sessão e o
            // serviço se encerra sozinho quando foregroundWanted ficar false.
            scope.launch {
                try {
                    graph.stopAllOutputs()
                } catch (t: Throwable) {
                    Log.e(TAG, "Falha ao parar as saídas pela notificação", t)
                }
                if (!watching) mainHandler.post { shutdown() }
            }
            return START_NOT_STICKY
        }

        // Iniciado por startForegroundService: o Android EXIGE startForeground logo.
        val promoted = promoteToForeground(
            camera = intent?.getBooleanExtra(EXTRA_CAMERA, true) ?: true,
            microphone = intent?.getBooleanExtra(EXTRA_MIC, true) ?: true,
        )
        if (!promoted) {
            Log.w(TAG, "Não foi possível promover a foreground; encerrando")
            shutdown()
            return START_NOT_STICKY
        }

        if (!watching) {
            watching = true
            scope.launch {
                val textJob = launch { graph.sessionConsumers.collect { updateNotification(it) } }
                delay(STARTUP_GRACE_MS)
                graph.foregroundWanted.first { !it }
                textJob.cancel()
                mainHandler.post { shutdown() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    private fun shutdown() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground falhou: ${e.message}")
        }
        stopSelf()
    }

    private fun promoteToForeground(camera: Boolean, microphone: Boolean): Boolean = try {
        val notification = buildNotification(graph.sessionConsumers.value)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                var type = 0
                if (camera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (microphone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (type == 0) {
                    false
                } else {
                    startForeground(NOTIFICATION_ID, notification, type)
                    true
                }
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
                true
            }

            else -> {
                startForeground(NOTIFICATION_ID, notification)
                true
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "startForeground falhou: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun updateNotification(consumers: SessionConsumers) {
        if (!consumers.holdsSession) return
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(consumers))
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao atualizar a notificação: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(CHANNEL_ID, "Sessão de captura BDSM", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Indica gravação ou transmissão em andamento (câmera e microfone em uso)"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(consumers: SessionConsumers): Notification {
        // Volta à Activity existente (não cria outra): SINGLE_TOP + REORDER_TO_FRONT.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureForegroundService::class.java).apply { action = ACTION_STOP_SESSION },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(CaptureSessionPolicy.notificationTitle(consumers))
            .setContentText(CaptureSessionPolicy.notificationText(consumers))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Parar", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
