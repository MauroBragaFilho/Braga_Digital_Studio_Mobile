package com.bragastudio.mobile.core.domain

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class HardwareMetrics(
    val batteryPercentage: Int = 100,
    val isCharging: Boolean = false,
    val temperatureCelsius: Float = 0f,
    val storageTotalGB: Float = 0f,
    val storageFreeGB: Float = 0f,
    val storageUsedPercentage: Int = 0,
    // REMOVIDO: cpuUsagePercentage, gpuUsagePercentage
    val isWifiConnected: Boolean = false,
    // REMOVIDO: wifiSsid
)

/**
 * Telemetria de hardware (bateria, temperatura, armazenamento, Wi-Fi).
 *
 * O polling só roda enquanto há coletores ([SharingStarted.WhileSubscribed]): com o app em
 * segundo plano e nenhuma tela observando, a CPU não é acordada a cada 2 s (M55). Uma falha
 * numa leitura não encerra a telemetria: o ciclo seguinte tenta de novo.
 */
@Singleton
class HardwareMonitorService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Escopo de aplicação: só hospeda o stateIn; o trabalho em si é cancelado sem coletores.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val metrics: StateFlow<HardwareMetrics> = flow {
        while (true) {
            emit(readMetrics())
            delay(POLL_INTERVAL_MS)
        }
    }
        .catch { e -> Log.w(TAG, "Telemetria de hardware interrompida", e) }
        .flowOn(Dispatchers.IO)
        .stateIn(appScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HardwareMetrics())

    /**
     * Lê todas as métricas. Cada bloco é isolado: falhar no armazenamento não perde a bateria.
     * Retorna o último estado conhecido nos campos que falharem.
     */
    private fun readMetrics(): HardwareMetrics {
        val previous = metrics.value
        // 1. Bateria e Temperatura
        val batteryStatus: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler a bateria", e)
            null
        }

        val batteryPct: Int = batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else previous.batteryPercentage
        } ?: previous.batteryPercentage

        val isCharging: Boolean = batteryStatus?.let { intent ->
            val status: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } ?: previous.isCharging

        val tempCelsius: Float = batteryStatus?.let { intent ->
            val temp: Int = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temp > 0) temp / 10f else previous.temperatureCelsius
        } ?: previous.temperatureCelsius

        // 2. Armazenamento (volume onde as gravações são escritas: Movies do app, não filesDir)
        var totalGb = previous.storageTotalGB
        var freeGb = previous.storageFreeGB
        var usedPct = previous.storageUsedPercentage
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val stat = StatFs(dir.path)
            val bytesTotal = stat.blockSizeLong * stat.blockCountLong
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            totalGb = (bytesTotal / BYTES_PER_GB).toFloat()
            freeGb = (bytesAvailable / BYTES_PER_GB).toFloat()
            usedPct = if (bytesTotal > 0) {
                (((bytesTotal - bytesAvailable).toDouble() / bytesTotal.toDouble()) * 100.0).toInt()
            } else {
                0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao medir o armazenamento", e)
        }

        return HardwareMetrics(
            batteryPercentage = batteryPct,
            isCharging = isCharging,
            temperatureCelsius = tempCelsius,
            storageTotalGB = totalGb,
            storageFreeGB = freeGb,
            storageUsedPercentage = usedPct,
            isWifiConnected = checkWifiStatus(),
        )
    }

    // ACCESS_NETWORK_STATE está declarada no manifest do :app.
    @android.annotation.SuppressLint("MissingPermission")
    private fun checkWifiStatus(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val TAG = "HardwareMonitor"
        const val POLL_INTERVAL_MS = 2_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0
    }
}
