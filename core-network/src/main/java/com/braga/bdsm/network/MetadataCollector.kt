package com.braga.bdsm.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linkServer: LinkServer
) {
    private val collectorScope = CoroutineScope(Dispatchers.Default + Job())
    private var isCollecting = false

    fun startCollecting() {
        if (isCollecting) return
        isCollecting = true
        
        collectorScope.launch {
            while (isActive && isCollecting) {
                val state = collectCurrentState()
                linkServer.currentState = state
                delay(500) // Collect at 2 Hz
            }
        }
    }

    fun stopCollecting() {
        isCollecting = false
    }

    private fun collectCurrentState(): LinkState {
        // Obter status da bateria
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        
        val batteryPct: Int = batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            (level * 100 / scale.toFloat()).toInt()
        } ?: 100
        
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        // Aqui podemos integrar com o core-capture para ler FPS, fonte e lente
        // Por agora retornamos os mockups integrados com a bateria real
        return LinkState(
            batteryLevel = batteryPct,
            isCharging = isCharging,
            // TODO: Integrar com state flows reais do app (CaptureManager)
            captureSource = "Internal Camera",
            cameraLens = "Wide",
            fps = 60,
            microphone = "Built-in"
        )
    }
}
