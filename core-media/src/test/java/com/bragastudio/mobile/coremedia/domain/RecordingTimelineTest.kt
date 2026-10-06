package com.bragastudio.mobile.coremedia.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTimelineTest {

    // ---------------------------------------------------------------- TimelineRebaser

    @Test
    fun rebaser_usesCommonOriginForBothTracks_preservingRelativeOffset() {
        val r = TimelineRebaser(originUs = 5_000_000L)
        // vídeo começa 80 ms depois da origem, áudio 5 ms depois: o offset de 75 ms precisa sobreviver
        assertEquals(80_000L, r.rebase(isVideo = true, ptsUs = 5_080_000L))
        assertEquals(5_000L, r.rebase(isVideo = false, ptsUs = 5_005_000L))
        assertEquals(113_333L, r.rebase(isVideo = true, ptsUs = 5_113_333L))
    }

    @Test
    fun rebaser_neverReturnsNegative() {
        val r = TimelineRebaser(originUs = 1_000_000L)
        assertEquals(0L, r.rebase(isVideo = false, ptsUs = 999_000L))
    }

    @Test
    fun rebaser_fallsBackToFirstPtsWhenClockIsFarFromOrigin() {
        val r = TimelineRebaser(originUs = 1_000_000L, maxSkewUs = 10_000_000L)
        val firstVideo = 9_000_000_000_000L // outro relógio (ex.: realtime)
        assertEquals(0L, r.rebase(true, firstVideo))
        assertEquals(33_333L, r.rebase(true, firstVideo + 33_333L))
        assertTrue(r.videoFellBack)
        // o áudio, no relógio certo, continua usando a origem comum
        assertEquals(20_000L, r.rebase(false, 1_020_000L))
        assertFalse(r.audioFellBack)
    }

    @Test
    fun rebaser_anchorResetsBases() {
        val r = TimelineRebaser(originUs = 0L)
        r.rebase(true, 100L)
        r.anchor(1_000L)
        assertEquals(500L, r.rebase(true, 1_500L))
    }

    // ---------------------------------------------------------------- PreMuxerQueue

    private fun v(pts: Long, key: Boolean = false, size: Int = 40) = EncodedSample(true, ByteArray(size), pts, if (key) EncodedSample.FLAG_KEY_FRAME else 0)

    private fun a(pts: Long, size: Int = 10) = EncodedSample(false, ByteArray(size), pts, 0)

    @Test
    fun queue_drainsInPtsOrderInterleavingTracks() {
        val q = PreMuxerQueue()
        q.offer(v(10, key = true))
        q.offer(a(20))
        q.offer(v(30))
        q.offer(a(40))
        val out = q.drainOrdered()
        assertEquals(listOf(10L, 20L, 30L, 40L), out.map { it.ptsUs })
        assertEquals(listOf(true, false, true, false), out.map { it.isVideo })
        assertEquals(0, q.size)
        assertEquals(0L, q.totalBytes)
    }

    @Test
    fun queue_keepsFirstIdrWhenWithinLimits() {
        val q = PreMuxerQueue(maxDurationUs = 2_000_000L, maxBytes = 1_000_000L)
        q.offer(v(0, key = true))
        q.offer(v(33_000))
        q.offer(v(66_000))
        val out = q.drainOrdered()
        assertEquals(3, out.size)
        assertTrue(out.first().isKeyFrame)
        assertEquals(0, q.droppedSamples)
    }

    @Test
    fun queue_overByteLimit_dropsWholeOldestGopNotJustOneFrame() {
        val q = PreMuxerQueue(maxDurationUs = 10_000_000L, maxBytes = 150L)
        q.offer(v(0, key = true))
        q.offer(v(1))
        q.offer(v(2))
        q.offer(v(3, key = true))
        // 4 x 40 = 160 > 150 -> descarta o GOP 0..2 inteiro; sobra o IDR de pts 3
        val out = q.drainOrdered()
        assertEquals(listOf(3L), out.map { it.ptsUs })
        assertTrue(out.first().isKeyFrame)
        assertFalse(q.consumeKeyFrameRequest())
    }

    @Test
    fun queue_dropsEverythingAndWaitsForKeyFrame_whenNoLaterIdrExists() {
        val q = PreMuxerQueue(maxDurationUs = 10_000_000L, maxBytes = 100L)
        q.offer(v(0, key = true))
        q.offer(v(1))
        q.offer(v(2))
        assertEquals(0, q.size)
        assertTrue("deve pedir novo keyframe ao encoder", q.consumeKeyFrameRequest())
        assertFalse("o pedido é consumido uma vez", q.consumeKeyFrameRequest())

        assertFalse("P-frame órfão é rejeitado", q.offer(v(3)))
        assertTrue("o próximo IDR volta a ser aceito", q.offer(v(4, key = true)))
        assertEquals(listOf(4L), q.drainOrdered().map { it.ptsUs })
    }

    @Test
    fun queue_overDurationLimit_dropsOldestAudioFirst() {
        val q = PreMuxerQueue(maxDurationUs = 1_000L, maxBytes = 1_000_000L)
        q.offer(a(0))
        q.offer(a(2_000))
        assertEquals(listOf(2_000L), q.drainOrdered().map { it.ptsUs })
    }

    @Test
    fun queue_clearEmptiesEverything() {
        val q = PreMuxerQueue()
        q.offer(v(0, key = true))
        q.offer(a(1))
        q.clear()
        assertEquals(0, q.size)
        assertEquals(0L, q.totalBytes)
    }

    // ---------------------------------------------------------------- TrackSyncPolicy

    @Test
    fun policy_waitsForVideoFormatFirst() {
        assertEquals(MuxDecision.WAIT, TrackSyncPolicy.decide(false, true, true, 99_999))
    }

    @Test
    fun policy_startsWithAudioAsSoonAsBothFormatsExist() {
        assertEquals(MuxDecision.START_WITH_AUDIO, TrackSyncPolicy.decide(true, true, true, 10))
    }

    @Test
    fun policy_waitsForAudioUntilTimeout_thenGoesVideoOnly() {
        assertEquals(MuxDecision.WAIT, TrackSyncPolicy.decide(true, false, true, 1_999))
        assertEquals(MuxDecision.START_VIDEO_ONLY, TrackSyncPolicy.decide(true, false, true, 2_000))
    }

    @Test
    fun policy_noAudioExpected_startsVideoOnlyImmediately() {
        assertEquals(MuxDecision.START_VIDEO_ONLY, TrackSyncPolicy.decide(true, false, false, 0))
    }

    @Test
    fun policy_customTimeout() {
        assertEquals(MuxDecision.START_VIDEO_ONLY, TrackSyncPolicy.decide(true, false, true, 500, audioTimeoutMs = 400))
    }

    // ---------------------------------------------------------------- PcmMath

    @Test
    fun pcm_durationOfOneSecondStereo48k() {
        assertEquals(1_000_000L, PcmMath.durationUs(48_000 * 4, channels = 2, sampleRate = 48_000))
        assertEquals(10_000L, PcmMath.durationUs(1_920, 2, 48_000)) // 10 ms
    }

    @Test
    fun pcm_sliceSizes_neverExceedCapacity_andAreFrameAligned() {
        val slices = PcmMath.sliceSizes(total = 40_000, capacity = 16_384, channels = 2)
        assertEquals(40_000, slices.sum())
        assertTrue(slices.all { it <= 16_384 })
        assertTrue(slices.dropLast(1).all { it % 4 == 0 })
        assertEquals(listOf(16_384, 16_384, 7_232), slices)
    }

    @Test
    fun pcm_sliceSizes_capacityNotMultipleOfFrame_isRoundedDown() {
        val slices = PcmMath.sliceSizes(total = 100, capacity = 10, channels = 2)
        assertTrue(slices.all { it == 8 || it == 4 })
        assertEquals(100, slices.sum())
    }

    @Test
    fun pcm_sliceSizes_degenerateInputs() {
        assertTrue(PcmMath.sliceSizes(0, 1024, 2).isEmpty())
        assertTrue(PcmMath.sliceSizes(100, 3, 2).isEmpty()) // menos que um frame
    }

    // ---------------------------------------------------------------- SpaceGuard

    @Test
    fun spaceGuard_floorIs200Mb() {
        assertEquals(200L * 1024 * 1024, SpaceGuard.MIN_FREE_BYTES)
        assertTrue(SpaceGuard.isLow(SpaceGuard.MIN_FREE_BYTES - 1))
        assertFalse(SpaceGuard.isLow(SpaceGuard.MIN_FREE_BYTES))
        assertTrue(SpaceGuard.isLow(5_000L, floorBytes = 10_000L))
    }
}
