package com.bragastudio.mobile.network.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bragastudio.mobile.network.auth.LinkAuthManager
import com.bragastudio.mobile.network.auth.PairRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Notificação de pedido de pareamento para quando o app NÃO está visível (2.2). Com o app
 * em primeiro plano o diálogo da MainActivity cobre o caso; em segundo plano esta
 * notificação traz as ações Permitir/Recusar ([PairingActionReceiver]).
 *
 * Segurança: a notificação é PRIVATE com uma versão pública sem ações, então numa tela
 * bloqueada o operador não consegue aprovar um pareamento sem desbloquear o aparelho.
 */
@Singleton
class PairingNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: LinkAuthManager,
    private val visibility: AppVisibility,
) {
    companion object {
        private const val TAG = "PairingNotifier"
        private const val CHANNEL_ID = "bdsm_link_pairing"
        private const val NOTIFICATION_BASE = 3000
        private const val SCHEME = "bdsm-pair"

        internal const val ACTION_APPROVE = "com.bragastudio.mobile.network.service.action.PAIR_APPROVE"
        internal const val ACTION_DENY = "com.bragastudio.mobile.network.service.action.PAIR_DENY"
        internal const val EXTRA_REQUEST_ID = "requestId"

        internal fun notificationId(requestId: String): Int = NOTIFICATION_BASE + (requestId.hashCode() and 0x0FFFFFFF) % 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val posted = HashMap<String, Int>()

    /** Começa a observar pedidos (chamado pelo [LinkServerService]). */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Expira pedidos mesmo sem ninguém consultando (o app pode estar em background).
            launch {
                while (true) {
                    delay(5_000)
                    if (auth.pending.value.isNotEmpty()) auth.refresh()
                }
            }
            combine(auth.pending, visibility.foreground) { pending, foreground -> pending to foreground }
                .collect { (pending, foreground) -> sync(pending, foreground) }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        cancelAll()
    }

    @Synchronized
    private fun sync(pending: List<PairRequest>, foreground: Boolean) {
        val ids = pending.map { it.id }.toSet()
        // Resolvidos/expirados saem da bandeja.
        posted.keys.filter { it !in ids }.forEach { cancel(it) }
        if (foreground) {
            // O diálogo da MainActivity está visível: sem notificação duplicada.
            posted.keys.toList().forEach { cancel(it) }
            return
        }
        pending.filter { it.id !in posted }.forEach { post(it) }
    }

    @SuppressLint("MissingPermission")
    private fun post(req: PairRequest) {
        ensureChannel()
        val id = notificationId(req.id)

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val approve = actionIntent(req.id, ACTION_APPROVE, id * 2)
        val deny = actionIntent(req.id, ACTION_DENY, id * 2 + 1)

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BDSM Link")
            .setContentText("Pedido de conexão (desbloqueie para responder)")
            .build()

        val text = "\"${req.clientName}\" (${req.remoteAddress}) - código ${req.code}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Conectar ao BDSM Link?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nConfira se o mesmo código aparece no OBS."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter((req.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(1_000L))
            .setContentIntent(open)
            .addAction(0, "Permitir", approve)
            .addAction(0, "Recusar", deny)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
            posted[req.id] = id
        } catch (e: SecurityException) {
            Log.w(TAG, "Notificação bloqueada (POST_NOTIFICATIONS): ${e.message}")
        }
    }

    /** PendingIntent IMMUTABLE para um receiver NÃO exportado, único por pedido e ação. */
    private fun actionIntent(requestId: String, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PairingActionReceiver::class.java).apply {
            this.action = action
            // Sem `data` distinta, PendingIntents do mesmo `action` seriam consideradas iguais.
            data = Uri.parse("$SCHEME://$requestId")
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancel(requestId: String) {
        posted.remove(requestId)?.let { NotificationManagerCompat.from(context).cancel(it) }
    }

    private fun cancelAll() {
        posted.keys.toList().forEach { cancel(it) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pedidos de pareamento", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisa quando o OBS ou um navegador pede para se conectar ao BDSM Link"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }
}
