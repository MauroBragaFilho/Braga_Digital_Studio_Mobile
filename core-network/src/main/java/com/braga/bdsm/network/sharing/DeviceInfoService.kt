package com.braga.bdsm.network.sharing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.bragastudio.mobile.core.repository.RecordingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DeviceInfoResponse(
    val deviceName: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val totalStorageBytes: Long,
    val freeStorageBytes: Long,
    val videoCount: Int
)

@Singleton
class DeviceInfoService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingRepository: RecordingRepository
) {
    suspend fun getDeviceInfo(): DeviceInfoResponse {
        val (batteryLevel, isCharging) = getBatteryInfo()
        val (totalStorage, freeStorage) = getStorageInfo()
        
        // As recordings might be exposed as Flow in repository, we get the current list size
        val recordings = recordingRepository.getAllRecordings().first()
        val videoCount = recordings.size

        return DeviceInfoResponse(
            deviceName = Build.DEVICE,
            deviceModel = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            appVersion = getAppVersion(),
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            totalStorageBytes = totalStorage,
            freeStorageBytes = freeStorage,
            videoCount = videoCount
        )
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else 0

        return Pair(batteryPct, isCharging)
    }

    private fun getStorageInfo(): Pair<Long, Long> {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        
        return Pair(totalBlocks * blockSize, availableBlocks * blockSize)
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
