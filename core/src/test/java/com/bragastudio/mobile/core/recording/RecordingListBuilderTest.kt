package com.bragastudio.mobile.core.recording

import com.bragastudio.mobile.core.database.RecordingEntity
import com.bragastudio.mobile.core.database.RecordingStatus
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingListBuilderTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    // 2026-10-03 12:00:00 UTC e 2026-10-02 12:00:00 UTC
    private val day1 = 1_791_028_800_000L
    private val day0 = day1 - 24L * 3600 * 1000

    private fun rec(
        id: String,
        createdAt: Long,
        status: String = RecordingStatus.COMPLETED,
        favorite: Boolean = false,
        name: String = "BDSM_$id.mp4",
    ) = RecordingEntity(
        id = id,
        fileName = name,
        filePath = "/x/$name",
        thumbnailPath = "",
        durationMs = 3_725_000,
        sizeBytes = 1_000,
        resolution = "1080p",
        frameRate = 30,
        codec = "H.265",
        bitrate = 50,
        audioCodec = "AAC",
        audioSampleRate = 48_000,
        createdAt = createdAt,
        isFavorite = favorite,
        status = status,
    )

    @Test
    fun toItem_usaMetadadosReaisDoRegistro() {
        val item = RecordingListBuilder.toItem(rec("a", day1), utc)
        assertEquals("1:02:05", item.durationLabel)
        assertEquals("1080p", item.resolutionLabel)
        assertEquals("H.265", item.codecLabel)
        assertEquals("30fps", item.fpsLabel)
        assertEquals("50 Mbps", item.bitrateLabel)
        assertFalse(item.isCorrupted)
    }

    @Test
    fun build_escondeInProgressEDeleted_eMarcaCorrompidas() {
        val rows = listOf(
            rec("ok", day1),
            rec("rec", day1, status = RecordingStatus.IN_PROGRESS),
            rec("del", day1, status = RecordingStatus.DELETED),
            rec("bad", day1, status = RecordingStatus.CORRUPTED),
        )
        val items = RecordingListBuilder.build(rows, GalleryTab.ALL, "", true, utc, now = FAR_FUTURE).flatMap { it.items }
        assertEquals(setOf("ok", "bad"), items.map { it.id }.toSet())
        assertTrue(items.first { it.id == "bad" }.isCorrupted)
    }

    @Test
    fun build_filtraFavoritasEBusca() {
        val rows = listOf(
            rec("a", day1, favorite = true, name = "Show_Ao_Vivo.mp4"),
            rec("b", day1 - 1000, name = "ensaio.mp4"),
        )
        val fav = RecordingListBuilder.build(rows, GalleryTab.FAVORITES, "", true, utc).flatMap { it.items }
        assertEquals(listOf("a"), fav.map { it.id })
        val search = RecordingListBuilder.build(rows, GalleryTab.ALL, "ENSAIO", true, utc).flatMap { it.items }
        assertEquals(listOf("b"), search.map { it.id })
        assertTrue(RecordingListBuilder.build(rows, GalleryTab.ALL, "zzz", true, utc).isEmpty())
    }

    @Test
    fun build_agrupaPorDia_eRespeitaAOrdem() {
        val rows = listOf(
            rec("d0", day0),
            rec("d1a", day1),
            rec("d1b", day1 + 1000),
        )
        val newest = RecordingListBuilder.build(rows, GalleryTab.ALL, "", true, utc, now = FAR_FUTURE)
        assertEquals(2, newest.size)
        assertEquals("3 de outubro de 2026", newest[0].label)
        assertEquals(listOf("d1b", "d1a"), newest[0].items.map { it.id })
        assertEquals(listOf("d0"), newest[1].items.map { it.id })

        val oldest = RecordingListBuilder.build(rows, GalleryTab.ALL, "", false, utc, now = FAR_FUTURE)
        assertEquals(listOf("d0"), oldest[0].items.map { it.id })
        assertEquals(listOf("d1a", "d1b"), oldest[1].items.map { it.id })
    }

    @Test
    fun dateLabels_hojeOntemEMesmoAno() {
        val now = day1 + 5 * 3_600_000L // 3 de outubro de 2026, depois do meio-dia UTC
        assertEquals("Hoje", RecordingDateLabels.group(day1, now, utc))
        assertEquals("Ontem", RecordingDateLabels.group(now - 24 * 3_600_000L, now, utc))
        assertEquals("1 de outubro", RecordingDateLabels.group(now - 2 * 24 * 3_600_000L, now, utc))
        assertEquals("3 de outubro de 2026", RecordingDateLabels.group(day1, FAR_FUTURE, utc))
    }

    @Test
    fun dateLabels_viradaDoDiaRespeitaOFuso() {
        val saoPaulo = TimeZone.getTimeZone("GMT-03:00")
        val midnightUtc = day1 - day1 % 86_400_000L
        val now = midnightUtc + 2 * 3_600_000L // 02:00 UTC de 3/10 = 23:00 de 2/10 em São Paulo
        assertEquals("Hoje", RecordingDateLabels.group(now - 3_600_000L, now, saoPaulo))
        val yesterdayNoonSp = midnightUtc - 33 * 3_600_000L // 12:00 de 1/10 em São Paulo
        assertEquals("Ontem", RecordingDateLabels.group(yesterdayNoonSp, now, saoPaulo))
        // No mesmo instante, em UTC já seriam dois dias atrás.
        assertEquals("1 de outubro", RecordingDateLabels.group(yesterdayNoonSp, now, utc))
    }

    private companion object {
        const val FAR_FUTURE = 4_000_000_000_000L
    }
}
