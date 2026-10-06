package com.bragastudio.mobile.featuresettings

import com.bragastudio.mobile.core.recording.RecordingGroup
import com.bragastudio.mobile.core.recording.RecordingItem
import com.bragastudio.mobile.coremedia.domain.LutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UxLogicTest {

    // ------------------------------------------------------------------ presets

    @Test
    fun preset_detectsExactMatches() {
        QualityPreset.entries.forEach {
            assertEquals(it, QualityPreset.detect(it.resolution, it.fps.value, it.codec, it.bitrateMbps))
        }
    }

    @Test
    fun preset_anyDifferenceIsCustom() {
        assertNull(QualityPreset.detect(Resolution.P1080, 60, Codec.H264, 50))
        assertNull(QualityPreset.detect(Resolution.P1080, 30, Codec.H264, 100))
        assertNull(QualityPreset.detect(Resolution.P1440, 30, Codec.H264, 50))
    }

    @Test
    fun preset_defaultSettingsAreBalanced() {
        // VideoSettings() padrão: 1080p, 30 fps, 50 Mbps, H.264.
        assertEquals(QualityPreset.BALANCED, QualityPreset.detect(Resolution.DEFAULT, Fps.DEFAULT.value, Codec.DEFAULT, 50))
    }

    @Test
    fun preset_shortFormat() {
        assertEquals("4K · 30 fps", QualityPreset.MAX.shortFormat)
        assertEquals("1080p · 30 fps", QualityPreset.BALANCED.shortFormat)
        assertEquals("1440p · 60 fps", QualityText.format(Resolution.P1440, 60))
    }

    @Test
    fun preset_sizePerMinute() {
        assertEquals(375, QualityText.mbPerMinute(50))
        assertEquals(187, QualityText.mbPerMinute(25))
        assertEquals(750, QualityText.mbPerMinute(100))
    }

    @Test
    fun presets_valuesAreOffered() {
        // Cada preset só usa valores que as listas do app oferecem (senão a UI não conseguiria exibi-los).
        QualityPreset.entries.forEach { assertTrue(it.bitrateMbps in NumericOptions.BITRATES_MBPS) }
    }

    // ------------------------------------------------------------------ apagar com desfazer

    @Test
    fun pendingRemoval_hidesAndRestores() {
        val pending = PendingRemoval()
        val items = listOf("a", "b", "c")
        assertEquals(items, pending.filterVisible(items) { it })
        pending.hide(listOf("a", "c"))
        assertEquals(listOf("b"), pending.filterVisible(items) { it })
        pending.restore(listOf("a"))
        assertEquals(listOf("a", "b"), pending.filterVisible(items) { it })
        pending.release(listOf("c"))
        assertTrue(pending.hidden.value.isEmpty())
    }

    // ------------------------------------------------------------------ NDI

    @Test
    fun ndi_phaseFollowsStateInPriorityOrder() {
        assertEquals(NdiPhase.OFF, NdiStatus.phase(enabled = false, hasNetwork = true, connections = 3))
        assertEquals(NdiPhase.OFF, NdiStatus.phase(enabled = false, hasNetwork = false, connections = 0))
        assertEquals(NdiPhase.NO_NETWORK, NdiStatus.phase(enabled = true, hasNetwork = false, connections = 2))
        assertEquals(NdiPhase.WAITING_RECEIVER, NdiStatus.phase(enabled = true, hasNetwork = true, connections = 0))
        assertEquals(NdiPhase.LIVE, NdiStatus.phase(enabled = true, hasNetwork = true, connections = 1))
    }

    @Test
    fun ndi_sourceLabelMatchesWhatReceiversShow() {
        assertEquals("BDSM (Galaxy A51)", NdiStatus.sourceLabel("Galaxy A51"))
        assertEquals("BDSM (…)", NdiStatus.sourceLabel("  "))
    }

    // ------------------------------------------------------------------ busca

    @Test
    fun search_emptyQueryMatchesEverything() {
        assertTrue(SettingsSearch.matches("", "Qualquer coisa"))
        assertTrue(SettingsSearch.matches("   ", "x"))
    }

    @Test
    fun search_ignoresCaseAndAccents() {
        assertTrue(SettingsSearch.matches("camera", "Fonte de vídeo", "Câmera do celular"))
        assertTrue(SettingsSearch.matches("CÂMERA", "camera"))
        assertTrue(SettingsSearch.matches("gravacoes", "Onde salvar as gravações"))
    }

    @Test
    fun search_allTermsMustMatch() {
        assertTrue(SettingsSearch.matches("onde salvar", "Onde salvar as gravações"))
        assertFalse(SettingsSearch.matches("onde zebra", "Onde salvar as gravações"))
        assertFalse(SettingsSearch.matches("zebra", "Qualidade do vídeo"))
    }

    // ------------------------------------------------------------------ ordenação

    private fun item(id: String, size: Long, created: Long) = RecordingItem(
        id = id, name = "$id.mp4", filePath = "/x/$id.mp4", contentUri = null, thumbnailPath = "",
        durationMs = 1000, durationLabel = "00:01", sizeBytes = size, resolutionLabel = "1080p",
        codecLabel = "H.264", fpsLabel = "30", bitrateLabel = "50", createdAt = created, dateLabel = "",
        dateGroup = "d", isFavorite = false, isCorrupted = false,
    )

    @Test
    fun sort_newestAndOldestKeepBuilderGroups() {
        val groups = listOf(RecordingGroup("dia", listOf(item("a", 1, 2), item("b", 2, 1))))
        assertEquals(groups, RecordingSorting.arrange(groups, RecordingSort.NEWEST))
        assertEquals(groups, RecordingSorting.arrange(groups, RecordingSort.OLDEST))
        assertTrue(RecordingSorting.newestFirstForBuilder(RecordingSort.NEWEST))
        assertTrue(RecordingSorting.newestFirstForBuilder(RecordingSort.LARGEST))
        assertFalse(RecordingSorting.newestFirstForBuilder(RecordingSort.OLDEST))
    }

    @Test
    fun sort_largestFlattensAndOrdersBySize() {
        val groups = listOf(
            RecordingGroup("d1", listOf(item("a", 10, 5), item("b", 30, 4))),
            RecordingGroup("d2", listOf(item("c", 20, 3), item("d", 30, 9))),
        )
        val out = RecordingSorting.arrange(groups, RecordingSort.LARGEST)
        assertEquals(1, out.size)
        assertEquals(RecordingSorting.SIZE_GROUP_LABEL, out[0].label)
        // empate de tamanho (30): o mais novo primeiro
        assertEquals(listOf("d", "b", "c", "a"), out[0].items.map { it.id })
    }

    @Test
    fun sort_largestOfNothingIsEmpty() {
        assertTrue(RecordingSorting.arrange(emptyList(), RecordingSort.LARGEST).isEmpty())
    }

    // ------------------------------------------------------------------ pré-visualização de LUT

    private fun identityLut(n: Int): LutParser.LutData {
        val data = FloatArray(n * n * n * 4)
        var i = 0
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    data[i++] = r / (n - 1f)
                    data[i++] = g / (n - 1f)
                    data[i++] = b / (n - 1f)
                    data[i++] = 1f
                }
            }
        }
        return LutParser.LutData(n, data)
    }

    @Test
    fun lutPreview_identityKeepsPixels() {
        val scene = LutPreview.sampleScene(24, 14)
        val out = LutPreview.apply(identityLut(9), scene)
        scene.indices.forEach { i ->
            for (shift in intArrayOf(16, 8, 0)) {
                val a = (scene[i] shr shift) and 0xFF
                val b = (out[i] shr shift) and 0xFF
                assertTrue("pixel $i canal $shift: $a vs $b", kotlin.math.abs(a - b) <= 2)
            }
        }
    }

    @Test
    fun lutPreview_invertLutInvertsPixels() {
        val n = 5
        val inv = identityLut(n)
        for (i in inv.floatData.indices) if (i % 4 != 3) inv.floatData[i] = 1f - inv.floatData[i]
        val white = intArrayOf(0xFFFFFFFF.toInt())
        val black = intArrayOf(0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), LutPreview.apply(inv, white)[0])
        assertEquals(0xFFFFFFFF.toInt(), LutPreview.apply(inv, black)[0])
    }

    @Test
    fun lutPreview_sceneHasExpectedSize() {
        assertEquals(LutPreview.WIDTH * LutPreview.HEIGHT, LutPreview.sampleScene().size)
    }
}

class SettingsCatalogTest {
    private val categories = listOf(
        SearchableCategory(SettingsCategory.CAMERA, "Câmera", "Fonte, resolução e FPS"),
        SearchableCategory(SettingsCategory.AUDIO, "Áudio", "Microfone e entrada"),
        SearchableCategory(SettingsCategory.RECORDING, "Gravação", "Codec, qualidade e armazenamento"),
    )
    private val items = listOf(
        SearchableItem(SettingsCategory.CAMERA, "resolution", "Resolução", "4k 1080p"),
        SearchableItem(SettingsCategory.RECORDING, "codec", "Formato do arquivo (codec)", "h264 h265"),
        SearchableItem(SettingsCategory.AUDIO, "mic", "Microfone de entrada"),
    )

    @Test
    fun blankQueryShowsAllCategoriesAndNoItems() {
        val r = SettingsCatalog.search("  ", categories, items)
        assertEquals(3, r.categories.size)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun matchesItemsAndKeepsOnlyTheirCategories() {
        val r = SettingsCatalog.search("h265", categories, items)
        assertEquals(listOf("codec"), r.items.map { it.key })
        assertEquals(listOf(SettingsCategory.RECORDING), r.categories.map { it.category })
    }

    @Test
    fun ignoresAccentsAndCase() {
        val r = SettingsCatalog.search("CAMERA", categories, items)
        assertEquals(listOf(SettingsCategory.CAMERA), r.categories.map { it.category })
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun categoryDescriptionIsSearchable() {
        val r = SettingsCatalog.search("armazenamento", categories, items)
        assertEquals(listOf(SettingsCategory.RECORDING), r.categories.map { it.category })
    }

    @Test
    fun noMatchReturnsEmpty() {
        val r = SettingsCatalog.search("zzz", categories, items)
        assertTrue(r.categories.isEmpty() && r.items.isEmpty())
    }

    @Test
    fun allTermsMustMatch() {
        val r = SettingsCatalog.search("microfone codec", categories, items)
        assertTrue(r.categories.isEmpty() && r.items.isEmpty())
        val ok = SettingsCatalog.search("formato codec", categories, items)
        assertEquals(listOf("codec"), ok.items.map { it.key })
    }

    @Test
    fun splitColumnsKeepsOrderAndGivesTheLargerHalfToTheFirst() {
        val (left, right) = SettingsCatalog.splitColumns(SettingsCategory.entries)
        assertEquals(listOf("camera", "audio", "monitor", "ndi"), left.map { it.id })
        assertEquals(listOf("recording", "app", "about"), right.map { it.id })
        assertEquals(SettingsCategory.entries, left + right)
    }

    @Test
    fun splitColumnsHandlesSmallLists() {
        assertEquals(emptyList<Int>() to emptyList<Int>(), SettingsCatalog.splitColumns(emptyList<Int>()))
        assertEquals(listOf(1) to emptyList<Int>(), SettingsCatalog.splitColumns(listOf(1)))
        assertEquals(listOf(1) to listOf(2), SettingsCatalog.splitColumns(listOf(1, 2)))
    }

    @Test
    fun categoryIdsRoundTrip() {
        SettingsCategory.entries.forEach { assertEquals(it, SettingsCategory.fromId(it.id)) }
        assertNull(SettingsCategory.fromId("x"))
    }
}

class NdiScreenLogicTest {
    @Test
    fun phases() {
        assertEquals(NdiScreenPhase.OFF, NdiScreenLogic.phase(enabled = false, active = false, failed = false))
        assertEquals(NdiScreenPhase.OFF, NdiScreenLogic.phase(enabled = false, active = true, failed = true))
        assertEquals(NdiScreenPhase.STARTING, NdiScreenLogic.phase(enabled = true, active = false, failed = false))
        assertEquals(NdiScreenPhase.ACTIVE, NdiScreenLogic.phase(enabled = true, active = true, failed = true))
        assertEquals(NdiScreenPhase.ERROR, NdiScreenLogic.phase(enabled = true, active = false, failed = true))
    }
}
