package com.bragastudio.mobile.network.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.bragastudio.mobile.network.auth.LinkAuthManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Trata os botões Permitir/Recusar da notificação de pareamento ([PairingNotifier]).
 * Declarado no manifest como `exported="false"`: só PendingIntents do próprio app o acionam.
 * Usa EntryPoint (em vez de @AndroidEntryPoint) para não depender de `super.onReceive`.
 */
class PairingActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun auth(): LinkAuthManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(PairingNotifier.EXTRA_REQUEST_ID) ?: return
        val auth = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).auth()
        when (intent.action) {
            PairingNotifier.ACTION_APPROVE -> auth.approve(id)
            PairingNotifier.ACTION_DENY -> auth.deny(id)
            else -> return
        }
        NotificationManagerCompat.from(context).cancel(PairingNotifier.notificationId(id))
    }
}
