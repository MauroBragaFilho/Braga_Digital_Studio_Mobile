package com.bragastudio.mobile.featurehome

import com.bragastudio.mobile.core.domain.HardwareMetrics
import com.bragastudio.mobile.core.domain.VideoSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStatusTest {
    private fun metrics(
        temp: Float = 30f,
        battery: Int = 80,
        charging: Boolean = false,
        free: Float = 50f,
        total: Float = 128f,
    ) = HardwareMetrics(
        batteryPercentage = battery,
        isCharging = charging,
        temperatureCelsius = temp,
        storageTotalGB = total,
        storageFreeGB = free,
    )

    @Test
    fun temperatureLevels() {
        assertEquals(Level.Ok, HomeStatus.temperatureLevel(39.9f))
        assertEquals(Level.Warning, HomeStatus.temperatureLevel(40f))
        assertEquals(Level.Critical, HomeStatus.temperatureLevel(45f))
    }

    @Test
    fun lowBatteryIgnoredWhileCharging() {
        assertEquals(Level.Critical, HomeStatus.batteryLevel(10, charging = false))
        assertEquals(Level.Warning, HomeStatus.batteryLevel(20, charging = false))
        assertEquals(Level.Ok, HomeStatus.batteryLevel(5, charging = true))
    }

    @Test
    fun unknownStorageDoesNotAlert() {
        assertEquals(Level.Ok, HomeStatus.storageLevel(freeGb = 0f, totalGb = 0f))
        assertEquals(Level.Critical, HomeStatus.storageLevel(freeGb = 2f, totalGb = 64f))
        assertEquals(Level.Warning, HomeStatus.storageLevel(freeGb = 8f, totalGb = 64f))
    }

    @Test
    fun healthTakesWorstLevel() {
        assertEquals(EquipmentHealth.Ok, HomeStatus.health(metrics()))
        assertEquals(EquipmentHealth.Warning, HomeStatus.health(metrics(temp = 41f)))
        assertEquals(EquipmentHealth.Critical, HomeStatus.health(metrics(temp = 41f, free = 1f)))
    }

    @Test
    fun formatSummaryMatchesHudPattern() {
        val video = VideoSettings(resolution = "4K", fps = 30, codec = "H.265")
        assertEquals("4K · 30 · H.265", HomeStatus.formatSummary(video))
    }

    @Test
    fun sourceKindIsTolerant() {
        assertEquals(SourceKind.Camera, HomeStatus.sourceKind("Camera"))
        assertEquals(SourceKind.Usb, HomeStatus.sourceKind("usb"))
        assertEquals(SourceKind.Sony, HomeStatus.sourceKind("SONY"))
        assertEquals(SourceKind.Camera, HomeStatus.sourceKind(null))
        assertEquals(SourceKind.Camera, HomeStatus.sourceKind("outra"))
    }

    @Test
    fun headline_priority() {
        assertEquals(HomeHeadline.READY, HomeStatus.headline(metrics(), null))
        assertEquals(HomeHeadline.ATTENTION, HomeStatus.headline(metrics(battery = 15), null))
        assertEquals(HomeHeadline.RECORDING, HomeStatus.headline(metrics(battery = 5), 1000L))
    }

    @Test
    fun attentionReason_order() {
        assertEquals(null, HomeStatus.attentionReason(metrics()))
        assertEquals(AttentionReason.STORAGE, HomeStatus.attentionReason(metrics(free = 2f, battery = 5, temp = 50f)))
        assertEquals(AttentionReason.BATTERY, HomeStatus.attentionReason(metrics(battery = 5, temp = 50f)))
        assertEquals(AttentionReason.TEMPERATURE, HomeStatus.attentionReason(metrics(temp = 41f)))
    }

    @Test
    fun activeRecording_ignoresStaleRows() {
        val now = 100_000_000L
        assertEquals(null, HomeStatus.activeRecordingStart(emptyList(), now))
        assertEquals(null, HomeStatus.activeRecordingStart(listOf(now - HomeStatus.STALE_RECORDING_MS - 1), now))
        assertEquals(now - 5000, HomeStatus.activeRecordingStart(listOf(now - 5000, now - 9000_000), now))
    }

    @Test
    fun remainingMinutes_and_format() {
        // 50 Mbps = 6,25 MB/s -> 100 GB ~ 286 min
        assertEquals(286, HomeStatus.remainingMinutes(100f, 50))
        assertEquals(0, HomeStatus.remainingMinutes(0f, 50))
        assertEquals("45 min", HomeStatus.formatRemaining(45))
        assertEquals("3 h 05 min", HomeStatus.formatRemaining(185))
        assertEquals("99+ h", HomeStatus.formatRemaining(60_000))
    }

    @Test
    fun formatElapsed() {
        assertEquals("00:12", HomeStatus.formatElapsed(12_000))
        assertEquals("1:02:03", HomeStatus.formatElapsed(3_723_000))
        assertEquals("00:00", HomeStatus.formatElapsed(-5))
    }

    @Test
    fun dayPeriod_boundaries() {
        assertEquals(DayPeriod.NIGHT, HomeStatus.dayPeriod(0))
        assertEquals(DayPeriod.NIGHT, HomeStatus.dayPeriod(4))
        assertEquals(DayPeriod.MORNING, HomeStatus.dayPeriod(5))
        assertEquals(DayPeriod.MORNING, HomeStatus.dayPeriod(11))
        assertEquals(DayPeriod.AFTERNOON, HomeStatus.dayPeriod(12))
        assertEquals(DayPeriod.AFTERNOON, HomeStatus.dayPeriod(17))
        assertEquals(DayPeriod.NIGHT, HomeStatus.dayPeriod(18))
        assertEquals(DayPeriod.NIGHT, HomeStatus.dayPeriod(23))
    }

    @Test
    fun metricAlerts_none_whenEverythingIsFine() {
        assertEquals(emptySet<MetricKind>(), HomeStatus.metricAlerts(metrics().copy(isWifiConnected = true)))
    }

    @Test
    fun metricAlerts_lowBatteryLowStorageHeatAndNoWifi() {
        val m = metrics(temp = 41f, battery = 15, free = 8f).copy(isWifiConnected = false)
        assertEquals(
            setOf(MetricKind.TEMPERATURE, MetricKind.STORAGE, MetricKind.BATTERY, MetricKind.WIFI),
            HomeStatus.metricAlerts(m),
        )
        assertEquals(setOf(MetricKind.BATTERY), HomeStatus.metricAlerts(metrics(battery = 20).copy(isWifiConnected = true)))
        assertEquals(setOf(MetricKind.STORAGE), HomeStatus.metricAlerts(metrics(free = 3f).copy(isWifiConnected = true)))
        assertEquals(setOf(MetricKind.TEMPERATURE), HomeStatus.metricAlerts(metrics(temp = 40f).copy(isWifiConnected = true)))
    }

    @Test
    fun metricAlerts_chargingSilencesBattery_unknownStorageIsSilent() {
        val m = metrics(battery = 5, charging = true, free = 0f, total = 0f).copy(isWifiConnected = true)
        assertEquals(emptySet<MetricKind>(), HomeStatus.metricAlerts(m))
    }

    @Test
    fun metricFormats() {
        assertEquals("38°C", HomeStatus.formatTemperature(38.4f))
        assertEquals("--", HomeStatus.formatTemperature(0f))
        assertEquals("21 GB", HomeStatus.formatStorageFree(21.4f, 128f))
        assertEquals("--", HomeStatus.formatStorageFree(0f, 0f))
    }
}
