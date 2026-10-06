package com.bragastudio.mobile.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.bragastudio.mobile.network.sharing.DeviceNames
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Publica o [LinkState] real no [LinkServer]: combina a telemetria do app
 * ([LinkTelemetry.snapshot] e tally) com a bateria do aparelho. Só trabalha enquanto
 * houver clientes WebSocket conectados (sem polling nem receivers ociosos).
 */
@Singleton
class MetadataCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linkServer: LinkServer,
    private val telemetry: LinkTelemetry,
) {
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private data class Battery(val level: Int, val charging: Boolean)

    @Synchronized
    fun startCollecting() {
        if (job?.isActive == true) return
        job = collectorScope.launch {
            linkServer.wsClientCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { hasClients ->
                    if (!hasClients) return@collectLatest
                    // Nome do aparelho: mesma fonte do /api/discovery/info (L10).
                    val deviceName = DeviceNames.resolve(context)
                    combine(telemetry.snapshot, telemetry.tally, batteryFlow()) { snap, tally, battery ->
                        LinkState(
                            deviceName = deviceName,
                            batteryLevel = battery.level,
                            isCharging = battery.charging,
                            captureSource = snap.captureSource,
                            cameraLens = snap.cameraLens,
                            fps = snap.fps,
                            microphone = snap.microphone,
                            isRecording = snap.isRecording,
                            ndiStreamName = snap.ndiStreamName,
                            tally = tally.name,
                        )
                    }.collect { linkServer.currentState = it }
                }
        }
    }

    @Synchronized
    fun stopCollecting() {
        // O coletor já pausa sozinho sem clientes; aqui encerramos de vez.
        job?.cancel()
        job = null
    }

    /** Bateria por broadcast (ACTION_BATTERY_CHANGED), valor inicial vindo do sticky. */
    private fun batteryFlow(): Flow<Battery> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let { trySend(parse(it)) }
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(sticky?.let { parse(it) } ?: Battery(0, false))
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun parse(intent: Intent): Battery {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(pct, charging)
    }
}
