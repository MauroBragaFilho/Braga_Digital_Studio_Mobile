package com.bragastudio.mobile.coremedia.bsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BspSessionPolicyTest {

    // ---- Admissão de sessões -----------------------------------------------------------

    @Test
    fun admitsUpToTwoSessionsThenBusy() {
        assertEquals(BspSessionPolicy.Admission.ADMIT, BspSessionPolicy.admit(emptyList(), "a"))
        assertEquals(BspSessionPolicy.Admission.ADMIT, BspSessionPolicy.admit(listOf("a"), "b"))
        assertEquals(BspSessionPolicy.Admission.BUSY, BspSessionPolicy.admit(listOf("a", "b"), "c"))
        assertEquals(2, BspLimits.MAX_SESSIONS)
    }

    @Test
    fun sameClientReplacesItsOwnStaleSessionEvenWhenFull() {
        assertEquals(BspSessionPolicy.Admission.REPLACE_SAME_CLIENT, BspSessionPolicy.admit(listOf("a", "b"), "b"))
        assertEquals(BspSessionPolicy.Admission.REPLACE_SAME_CLIENT, BspSessionPolicy.admit(listOf("a"), "a"))
    }

    @Test
    fun customMaxSessionsIsHonored() {
        assertEquals(BspSessionPolicy.Admission.BUSY, BspSessionPolicy.admit(listOf("a"), "b", maxSessions = 1))
    }

    // ---- Prazo de autenticação e heartbeat --------------------------------------------

    @Test
    fun authDeadlineIsFourSeconds() {
        assertEquals(4_000L, BspLimits.AUTH_TIMEOUT_MS)
        assertEquals(4_000L, BspSessionPolicy.authTimeLeftMs(1_000, 1_000))
        assertEquals(1_000L, BspSessionPolicy.authTimeLeftMs(1_000, 4_000))
        assertFalse(BspSessionPolicy.authExpired(1_000, 4_999))
        assertTrue(BspSessionPolicy.authExpired(1_000, 5_000))
        assertTrue(BspSessionPolicy.authExpired(1_000, 60_000))
        assertEquals("relógio que anda para trás não passa do teto", 4_000L, BspSessionPolicy.authTimeLeftMs(10_000, 5_000))
    }

    @Test
    fun heartbeatTimesOutAfterFiveSecondsOfSilence() {
        assertFalse(BspSessionPolicy.heartbeatExpired(0, 5_000))
        assertTrue(BspSessionPolicy.heartbeatExpired(0, 5_001))
        assertFalse(BspSessionPolicy.heartbeatExpired(10_000, 12_000))
    }

    // ---- Limite de mensagens ------------------------------------------------------------

    @Test
    fun rateLimiterAllowsTwentyPerSecondAndBlocksTheTwentyFirst() {
        var now = 0L
        val limiter = MessageRateLimiter(20) { now }
        repeat(20) { assertTrue("mensagem ${it + 1}", limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
        now = 999
        assertFalse("ainda dentro da janela de 1 s", limiter.tryAcquire())
        now = 1_000
        // as 20 mensagens de t=0 saíram da janela de 1 s: cabem mais 20, e a 21ª é barrada de novo
        repeat(20) { assertTrue("mensagem ${it + 1} da nova janela", limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun rateLimiterIsSlidingNotFixedWindow() {
        var now = 0L
        val limiter = MessageRateLimiter(5) { now }
        // 5 mensagens espalhadas em 0..800 ms
        for (t in longArrayOf(0, 200, 400, 600, 800)) {
            now = t
            assertTrue(limiter.tryAcquire())
        }
        now = 900
        assertFalse(limiter.tryAcquire())
        now = 1_000 // só a de t=0 saiu
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        now = 1_200 // saiu a de t=200
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun rateLimiterSteadyRateBelowTheLimitNeverBlocks() {
        var now = 0L
        val limiter = MessageRateLimiter(20) { now }
        repeat(500) {
            now += 60 // ~16,7 mensagens/s
            assertTrue(limiter.tryAcquire())
        }
    }

    // ---- Limite de IDR ----------------------------------------------------------------

    @Test
    fun keyframeLimiterAllowsOnePer500ms() {
        val limiter = KeyframeLimiter()
        assertTrue(limiter.tryAcquire(1_000))
        assertFalse(limiter.tryAcquire(1_100))
        assertFalse(limiter.tryAcquire(1_499))
        assertTrue(limiter.tryAcquire(1_500))
        assertFalse(limiter.tryAcquire(1_501))
        assertEquals(500L, BspLimits.MIN_KEYFRAME_INTERVAL_MS)
    }

    @Test
    fun limitsMatchTheSpec() {
        assertEquals(8 * 1024, BspLimits.MAX_LINE_BYTES)
        assertEquals(20, BspLimits.MAX_MESSAGES_PER_SECOND)
        assertEquals(5_000L, BspLimits.HEARTBEAT_TIMEOUT_MS)
        assertEquals(1_000L, BspLimits.SR_INTERVAL_MS)
        assertEquals(500L, BspLimits.META_MIN_INTERVAL_MS)
    }
}
