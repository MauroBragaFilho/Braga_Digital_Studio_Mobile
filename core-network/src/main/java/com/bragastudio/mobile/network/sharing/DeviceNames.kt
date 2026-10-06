package com.bragastudio.mobile.network.sharing

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Fonte única do nome do dispositivo exibido ao OBS/dashboard. Antes, o
 * `DeviceInfoService` usava `Settings.Global.DEVICE_NAME` e a telemetria do WebSocket
 * nunca preenchia o nome: os dois endpoints mostravam nomes diferentes.
 */
internal object DeviceNames {
    fun resolve(context: Context): String {
        val fallback = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }
}
