package com.bragastudio.mobile.network.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bragastudio.mobile.network.LinkServer
import com.bragastudio.mobile.network.LinkServerController
import com.bragastudio.mobile.network.MetadataCollector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground Service que mantém o [LinkServer] (Ktor, porta 8080) e o anúncio mDNS vivos
 * com o app em segundo plano ou a tela apagada.
 *
 * Contrato (A9):
 *  - o serviço é iniciado a partir de um ponto em PRIMEIRO PLANO (a `MainActivity`, via
 *    [LinkServerController.startIfEnabled]); NUNCA do `Application.onCreate`;
 *  - a notificação permanece (canal silencioso, IMPORTANCE_MIN) como indicador de que o
 *    Link está no ar e traz a ação "Desligar";
 *  - a preferência `enabled` é conferida DENTRO do `onStartCommand`, também no restart
 *    `START_STICKY` (intent nulo);
 *  - se o sistema recusar o foreground (Android 12+ em background), o serviço se encerra
 *    e NÃO pede reinício: o servidor volta quando o app for aberto;
 *  - parar o servidor é assíncrono (nunca bloqueia a thread principal).
 */
@AndroidEntryPoint
class LinkServerService : Service() {

    @Inject lateinit var linkServer: LinkServer

    @Inject lateinit var controller: LinkServerController

    @Inject lateinit var pairingNotifier: PairingNotifier

    @Inject lateinit var metadataCollector: MetadataCollector

    companion object {
        private const val TAG = "LinkServerService"

        // O sufixo "_v2" é intencional: a importância de um canal JÁ CRIADO não pode ser
        // alterada por código; renomear garante o IMPORTANCE_MIN em instalações antigas.
        private const val CHANNEL_ID = "bdsm_link_server_channel_v2"
        private const val LEGACY_CHANNEL_ID = "bdsm_link_server_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.bragastudio.mobile.network.service.action.START"
        const val ACTION_STOP = "com.bragastudio.mobile.network.service.action.STOP"

        /** Ação da notificação: desliga o Link (persiste enabled = false) e encerra o serviço. */
        const val ACTION_DISABLE = "com.bragastudio.mobile.network.service.action.DISABLE"

        /**
         * Inicia o serviço em foreground. Retorna false (sem lançar) quando o sistema
         * recusa — ex.: `ForegroundServiceStartNotAllowedException` com o app em background.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, LinkServerService::class.java).apply { action = ACTION_START }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                // Não se captura a exceção por nome: ela só existe a partir do API 31 (minSdk 26).
                Log.w(TAG, "Sistema recusou iniciar o serviço do Link: ${e.javaClass.simpleName}")
                false
            }
        }

        /** Para o serviço (o `onDestroy` encerra o servidor de forma assíncrona). */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LinkServerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao parar o serviço do Link: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        pairingNotifier.start()
        // Telemetria do WebSocket: só trabalha enquanto houver clientes conectados.
        metadataCollector.startCollecting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_DISABLE) {
            // setEnabled(false) persiste e chama stopService; o onDestroy para o servidor.
            controller.setEnabled(false)
            return START_NOT_STICKY
        }

        // Iniciado por startForegroundService (ACTION_START): o Android EXIGE startForeground
        // logo, mesmo que depois decidamos parar (senão crasha por não promover a tempo).
        val promoted = promoteToForeground()

        if (action == ACTION_STOP || !controller.enabled.value) {
            Log.i(TAG, "Link desabilitado: encerrando o serviço")
            shutdown()
            return START_NOT_STICKY
        }

        if (!promoted) {
            // Sem foreground (ex.: restart sticky em background no Android 12+). Rodar sem
            // notificação seria arriscado: encerra; MainActivity.onStart reinicia depois.
            Log.w(TAG, "Não foi possível promover a foreground; encerrando")
            shutdown()
            return START_NOT_STICKY
        }

        linkServer.start() // assíncrono; o resultado aparece em LinkServer.state
        // START_STICKY: se o sistema matar o processo, tenta recriar o serviço (intent nulo,
        // caso em que a preferência e o foreground são reconferidos acima).
        return START_STICKY
    }

    override fun onDestroy() {
        metadataCollector.stopCollecting()
        pairingNotifier.stop()
        linkServer.stop() // assíncrono: não bloqueia a thread principal
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        linkServer.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(): Boolean = try {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground falhou: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            val channel = NotificationChannel(CHANNEL_ID, "BDSM Link Server", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Indica que o servidor local do BDSM Link está ativo na rede"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val disableIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LinkServerService::class.java).apply { action = ACTION_DISABLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BDSM Link ativo")
            .setContentText("Disponível na rede local (porta ${LinkServer.PORT})")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Desligar", disableIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Android 15 (targetSdk 35): o tipo `dataSync` tem limite de ~6 h por dia. Ao estourar, o sistema
     * chama este callback e exige `stopSelf()` em poucos segundos (senão lança ANR/exceção). O servidor
     * é encerrado; volta quando o app for aberto de novo (MainActivity.onStart).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Limite de tempo do foreground service (tipo=$fgsType): encerrando o Link")
        shutdown()
    }
}
