package com.braga.bdsm.network.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emissor das notificações de transferência do BDSM Link.
 *
 * Antes, o único feedback do Link era a notificação persistente do
 * [LinkServerService] ("BDSM Link ativo"), que agora fica oculta (ver
 * `LinkServerService`). As notificações realmente úteis — quando uma gravação
 * é copiada para o computador ou quando LUTs terminam de sincronizar — são
 * disparadas daqui, a partir dos endpoints de download/upload em
 * [com.braga.bdsm.network.LinkServer].
 */
@Singleton
class TransferNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "TransferNotifier"

        private const val CHANNEL_RECORDINGS_ID = "bdsm_link_transfers_recordings"
        private const val CHANNEL_LUTS_ID = "bdsm_link_transfers_luts"

        private const val NOTIFICATION_ID_RECORDINGS = 2001
        private const val NOTIFICATION_ID_LUTS = 2002
    }

    /** "Gravações Copiadas ao computador" — disparada quando um download conclui. */
    fun notifyRecordingsCopied(fileName: String) {
        ensureChannels()
        notify(
            id = NOTIFICATION_ID_RECORDINGS,
            channelId = CHANNEL_RECORDINGS_ID,
            title = "Gravações Copiadas ao computador",
            text = fileName,
            smallIcon = android.R.drawable.stat_sys_download_done
        )
    }

    /** "LUTs sincronizados" — disparada a cada LUT recebido do computador. */
    fun notifyLutsSynced(count: Int) {
        ensureChannels()
        notify(
            id = NOTIFICATION_ID_LUTS,
            channelId = CHANNEL_LUTS_ID,
            title = "LUTs sincronizados",
            text = if (count == 1) "1 LUT recebido do computador"
            else "$count LUTs recebidos do computador",
            smallIcon = android.R.drawable.stat_sys_upload_done
        )
    }

    @SuppressLint("MissingPermission")
    private fun notify(id: Int, channelId: String, title: String, text: String, smallIcon: Int) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Se o usuário negou POST_NOTIFICATIONS (API 33+), o notify() vira no-op;
        // em algumas ROMs pode lançar SecurityException, então blindamos aqui para
        // nunca derrubar a request HTTP que gerou a notificação.
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notificação bloqueada (POST_NOTIFICATIONS): ${e.message}")
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_RECORDINGS_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RECORDINGS_ID,
                    "Transferências de gravações",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Avisos quando uma gravação é copiada para o computador"
                }
            )
        }

        if (manager.getNotificationChannel(CHANNEL_LUTS_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LUTS_ID,
                    "Sincronização de LUTs",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Avisos quando LUTs são sincronizados com o computador"
                }
            )
        }
    }
}
