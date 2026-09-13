package com.braga.bdsm.network.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.braga.bdsm.network.LinkServer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground Service responsável por manter o [LinkServer] (Ktor embedded server,
 * porta 8080) e o anúncio mDNS vivos mesmo quando:
 *  - a Activity é destruída / o app é minimizado
 *  - a tela do celular apaga
 *  - o app é removido da lista de recentes (desde que o usuário não force-stop)
 *
 * Sem isso, o Android mata o processo (e o servidor junto) alguns segundos/minutos
 * após o app sair de primeiro plano, pois `LinkServer.start()` chamado direto em
 * `BsmApplication.onCreate()` não é suficiente para segurar o processo vivo.
 *
 * 📍 Este service é INICIADO por [BsmApplication] (ou pela Activity) via
 * `ContextCompat.startForegroundService(...)`, e não pelo próprio `Application.onCreate()`
 * diretamente — isso é necessário para podermos chamar `startForeground()` dentro da
 * janela de tempo exigida pelo Android (5s a partir do `startForegroundService`).
 */
@AndroidEntryPoint
class LinkServerService : Service() {

    @Inject
    lateinit var linkServer: LinkServer

    companion object {
        private const val TAG = "LinkServerService"

        // O sufixo "_v2" é intencional: a importância de um canal JÁ CRIADO não
        // pode ser alterada por código, então renomear garante que o novo
        // IMPORTANCE_MIN (notificação silenciosa, sem status bar) passe a valer
        // também em instalações que já tinham o canal antigo (IMPORTANCE_LOW).
        private const val CHANNEL_ID = "bdsm_link_server_channel_v2"
        private const val LEGACY_CHANNEL_ID = "bdsm_link_server_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.braga.bdsm.network.service.action.START"
        const val ACTION_STOP = "com.braga.bdsm.network.service.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LinkServerService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LinkServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                linkServer.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Precisa chamar startForeground() logo no início do onStartCommand,
                // antes de qualquer outra coisa que possa demorar.
                startForeground(NOTIFICATION_ID, buildNotification())

                // O serviço precisa ser promovido a foreground antes de iniciar o
                // servidor, mas a notificação não deve permanecer publicada depois
                // dessa etapa. DETACH apenas desvincula a notificação e a deixa
                // visível na gaveta; REMOVE efetivamente a remove.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }

                try {
                    linkServer.start()
                    Log.i(TAG, "LinkServer iniciado dentro do Foreground Service")
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao iniciar LinkServer", e)
                }
            }
        }
        // START_STICKY: se o sistema matar o processo por pressão de memória,
        // ele tenta recriar o service (sem o Intent original) assim que houver recursos.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        linkServer.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            // Remove o canal antigo (IMPORTANCE_LOW) para não deixar resíduo
            // nem permitir que a notificação antiga continue visível após o update.
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

            val channel = NotificationChannel(
                CHANNEL_ID,
                "BDSM Link Server",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Mantém o servidor local do BDSM Link ativo na rede"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BDSM Link ativo")
            .setContentText("Disponível na rede local (porta 8080)")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
