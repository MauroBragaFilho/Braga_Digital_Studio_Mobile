package com.bragastudio.mobile.core.domain

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileReader
import javax.inject.Inject
import javax.inject.Singleton

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

@Singleton
class HardwareMonitorService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _metrics = MutableStateFlow(HardwareMetrics())
    val metrics: StateFlow<HardwareMetrics> = _metrics.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        scope.launch {
            while (true) {
                updateMetrics()
                delay(2000) // Atualiza a cada 2 segundos
            }
        }
    }

    private fun updateMetrics() {
        // 1. Bateria e Temperatura
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }

        val batteryPct: Int = batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (scale > 0) (level * 100 / scale.toFloat()).toInt() else 100
        } ?: 100

        val isCharging: Boolean = batteryStatus?.let { intent ->
            val status: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false

        val tempCelsius: Float = batteryStatus?.let { intent ->
            val temp: Int = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temp > 0) temp / 10f else 0f
        } ?: 0f

        // 2. Armazenamento
        var totalGb = 0f
        var freeGb = 0f
        var usedPct = 0
        
        try {
            val path = context.filesDir.path
            val stat = StatFs(path)
            
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            
            val bytesTotal = blockSize * totalBlocks
            val bytesAvailable = blockSize * availableBlocks
            
            totalGb = bytesTotal / (1024f * 1024f * 1024f)
            freeGb = bytesAvailable / (1024f * 1024f * 1024f)
            
            if (bytesTotal > 0) {
                usedPct = (((bytesTotal - bytesAvailable).toFloat() / bytesTotal.toFloat()) * 100f).toInt()
            }
        } catch (e: Exception) { 
            e.printStackTrace()
        }

        // 3. Wi-Fi (SEM SSID)
        val isWifiConnected = checkWifiStatus()

        // REMOVIDO: Leitura de CPU e GPU

        _metrics.value = HardwareMetrics(
            batteryPercentage = batteryPct,
            isCharging = isCharging,
            temperatureCelsius = tempCelsius,
            storageTotalGB = totalGb,
            storageFreeGB = freeGb,
            storageUsedPercentage = usedPct,
            // REMOVIDO: cpuUsagePercentage, gpuUsagePercentage
            isWifiConnected = isWifiConnected,
            // REMOVIDO: wifiSsid
        )
    }

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

    // REMOVIDO: readCpuUsage(), readGpuUsage()
}