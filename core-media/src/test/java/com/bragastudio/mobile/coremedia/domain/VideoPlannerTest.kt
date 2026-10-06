package com.bragastudio.mobile.coremedia.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlannerTest {

    /** Encoder falso: aceita apenas as combinações listadas. */
    private class FakeProbe(
        private val supported: Set<Triple<String, Int, Int>>, // (mime, altura, fps)
        private val levels: List<Pair<Int, Int>> = listOf(
            VideoPlanner.AVC_PROFILE_BASELINE to 0x100,
            VideoPlanner.AVC_PROFILE_MAIN to 0x200,
            VideoPlanner.AVC_PROFILE_HIGH to 0x400,
            VideoPlanner.HEVC_PROFILE_MAIN to 0x800,
        ),
        private val maxBitrate: Int? = null,
    ) : EncoderProbe {
        val queries = mutableListOf<String>()
        override fun find(mime: String, width: Int, height: Int, fps: Int, hdr10: Boolean): EncoderInfo? {
            queries += "$mime/$height/$fps/$hdr10"
            if (Triple(mime, height, fps) !in supported) return null
            val lv = if (hdr10) levels + (VideoPlanner.HEVC_PROFILE_MAIN10 to 0x800) else levels
            return EncoderInfo("fake.$mime", lv, maxBitrate)
        }
    }

    private val hevc = VideoPlanner.MIME_HEVC
    private val avc = VideoPlanner.MIME_AVC

    // ---- tabela de resolução ----

    @Test
    fun resolutionTable_recordingLabels() {
        assertEquals(3840 to 2160, ResolutionTable.recording("4K"))
        assertEquals(2560 to 1440, ResolutionTable.recording("1440p"))
        assertEquals(1920 to 1080, ResolutionTable.recording("1080p"))
    }

    @Test
    fun resolutionTable_streamPresets() {
        assertEquals(1280 to 720, ResolutionTable.stream("HD"))
        assertEquals(1920 to 1080, ResolutionTable.stream("FHD"))
        assertEquals(2560 to 1440, ResolutionTable.stream("QHD"))
        assertEquals(3840 to 2160, ResolutionTable.stream("UHD"))
    }

    @Test
    fun resolutionTable_bothVocabulariesAgree() {
        assertEquals(ResolutionTable.recording("4K"), ResolutionTable.stream("UHD"))
        assertEquals(ResolutionTable.recording("1440p"), ResolutionTable.stream("QHD"))
        assertEquals(ResolutionTable.recording("1080p"), ResolutionTable.stream("FHD"))
    }

    @Test
    fun resolutionTable_unknownAndNullFallBackToFullHd() {
        assertEquals(1920 to 1080, ResolutionTable.resolve("???"))
        assertEquals(1920 to 1080, ResolutionTable.resolve(null))
        assertEquals(1920 to 1080, ResolutionTable.resolve(""))
    }

    @Test
    fun resolutionTable_isCaseInsensitive() {
        assertEquals(3840 to 2160, ResolutionTable.resolve("4k"))
        assertEquals(2560 to 1440, ResolutionTable.resolve("1440P"))
    }

    // ---- planejamento ----

    @Test
    fun portraitFrame_swapsWidthAndHeight_butProbesWithTableSize() {
        val probe = FakeProbe(setOf(Triple(avc, 1080, 30)))
        val plan = VideoPlanner.plan("H.264", "1080p", 30, 20, hdr = false, probe = probe, portraitFrame = true)!!
        assertEquals(1080, plan.width)
        assertEquals(1920, plan.height)
        assertEquals(listOf("$avc/1080/30/false"), probe.queries)
    }

    @Test
    fun landscapeFrame_isDefault() {
        val probe = FakeProbe(setOf(Triple(avc, 1080, 30)))
        val plan = VideoPlanner.plan("H.264", "1080p", 30, 20, hdr = false, probe = probe)!!
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
    }

    @Test
    fun hevcRequestedAndSupported_isKept_withMainProfile() {
        val probe = FakeProbe(setOf(Triple(hevc, 2160, 60), Triple(avc, 2160, 60)))
        val plan = VideoPlanner.plan("H.265", "4K", 60, 80, hdr = false, probe = probe)!!
        assertEquals(hevc, plan.mime)
        assertEquals(60, plan.fps)
        assertEquals(3840, plan.width)
        assertEquals(VideoPlanner.HEVC_PROFILE_MAIN, plan.profile)
        assertEquals(0x800, plan.level)
        assertTrue(plan.notices.isEmpty())
        assertEquals(80_000_000, plan.bitrateBps)
    }

    @Test
    fun hevcUnsupported_fallsBackToAvc_withNotice() {
        val probe = FakeProbe(setOf(Triple(avc, 2160, 60)))
        val plan = VideoPlanner.plan("H.265", "4K", 60, 50, hdr = false, probe = probe)!!
        assertEquals(avc, plan.mime)
        assertEquals(60, plan.fps)
        assertEquals(1, plan.notices.size)
        assertTrue(plan.notices[0].contains("H.264"))
    }

    @Test
    fun avc_picksHighProfileFirst_thenMain_thenBaseline() {
        val onlyBaselineAndMain = FakeProbe(
            setOf(Triple(avc, 1080, 30)),
            levels = listOf(VideoPlanner.AVC_PROFILE_BASELINE to 1, VideoPlanner.AVC_PROFILE_MAIN to 4),
        )
        val plan = VideoPlanner.plan("H.264", "1080p", 30, 20, false, onlyBaselineAndMain)!!
        assertEquals(VideoPlanner.AVC_PROFILE_MAIN, plan.profile)
        assertEquals(4, plan.level)

        val full = FakeProbe(setOf(Triple(avc, 1080, 30)))
        assertEquals(VideoPlanner.AVC_PROFILE_HIGH, VideoPlanner.plan("H.264", "1080p", 30, 20, false, full)!!.profile)
    }

    @Test
    fun unknownProfiles_leaveProfileAndLevelUnset() {
        val probe = FakeProbe(setOf(Triple(avc, 1080, 30)), levels = emptyList())
        val plan = VideoPlanner.plan("H.264", "1080p", 30, 20, false, probe)!!
        assertNull(plan.profile)
        assertNull(plan.level)
    }

    @Test
    fun fps60Unsupported_dropsTo30_keepingCodecWhenPossible() {
        val probe = FakeProbe(setOf(Triple(hevc, 2160, 30), Triple(avc, 2160, 30)))
        val plan = VideoPlanner.plan("H.265", "4K", 60, 50, false, probe)!!
        assertEquals(hevc, plan.mime)
        assertEquals(30, plan.fps)
        assertTrue(plan.notices.any { it.contains("30 fps") })
    }

    @Test
    fun triesCodecFallbackBeforeFpsFallback() {
        val probe = FakeProbe(setOf(Triple(avc, 2160, 60), Triple(hevc, 2160, 30)))
        val plan = VideoPlanner.plan("H.265", "4K", 60, 50, false, probe)!!
        // (HEVC,60) falha -> (AVC,60) serve; só depois seria (HEVC,30)
        assertEquals(avc, plan.mime)
        assertEquals(60, plan.fps)
    }

    @Test
    fun nothingSupported_returnsNull() {
        assertNull(VideoPlanner.plan("H.265", "4K", 60, 50, false, FakeProbe(emptySet())))
    }

    @Test
    fun hdr_requiresHevcMain10_andNeverFallsBackToAvc() {
        val probe = FakeProbe(setOf(Triple(avc, 2160, 30), Triple(hevc, 2160, 30)))
        val plan = VideoPlanner.plan("H.264", "4K", 30, 50, hdr = true, probe = probe)!!
        assertEquals(hevc, plan.mime)
        assertEquals(VideoPlanner.HEVC_PROFILE_MAIN10, plan.profile)
        assertTrue(probe.queries.none { it.startsWith(avc) })

        val noHevc = FakeProbe(setOf(Triple(avc, 2160, 30)))
        assertNull(VideoPlanner.plan("H.265", "4K", 30, 50, hdr = true, probe = noHevc))
    }

    @Test
    fun bitrateIsClampedToEncoderMaximum_withNotice() {
        val probe = FakeProbe(setOf(Triple(avc, 1080, 30)), maxBitrate = 40_000_000)
        val plan = VideoPlanner.plan("H.264", "1080p", 30, 100, false, probe)!!
        assertEquals(40_000_000, plan.bitrateBps)
        assertTrue(plan.notices.any { it.contains("40 Mbps") })
    }

    @Test
    fun fpsAtOrBelow30_neverTriesFallbackFps() {
        val probe = FakeProbe(emptySet())
        VideoPlanner.plan("H.264", "1080p", 24, 20, false, probe)
        assertTrue(probe.queries.all { it.contains("/24/") })
        assertNotNull(probe.queries.firstOrNull())
    }

    @Test
    fun pickProfile_choosesHighestLevelOfThePreferredProfile() {
        val (profile, level) = VideoPlanner.pickProfile(
            avc, false,
            listOf(
                VideoPlanner.AVC_PROFILE_HIGH to 0x100,
                VideoPlanner.AVC_PROFILE_HIGH to 0x4000,
                VideoPlanner.AVC_PROFILE_MAIN to 0x8000,
            ),
        )
        assertEquals(VideoPlanner.AVC_PROFILE_HIGH, profile)
        assertEquals(0x4000, level)
    }
}
