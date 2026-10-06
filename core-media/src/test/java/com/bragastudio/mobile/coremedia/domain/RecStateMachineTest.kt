package com.bragastudio.mobile.coremedia.domain

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecStateMachineTest {

    @Test
    fun startsIdle() {
        assertSame(RecState.Idle, RecStateMachine().current)
    }

    @Test
    fun happyPath_idlePreparingRecordingStoppingIdle() {
        val m = RecStateMachine()
        assertTrue(m.tryBeginPrepare())
        assertSame(RecState.Preparing, m.current)
        assertTrue(m.tryStart())
        assertSame(RecState.Recording, m.current)
        assertTrue(m.tryBeginStop())
        assertSame(RecState.Stopping, m.current)
        assertTrue(m.finishStop())
        assertSame(RecState.Idle, m.current)
    }

    @Test
    fun doubleTap_secondPrepareIsRejected() {
        val m = RecStateMachine()
        assertTrue(m.tryBeginPrepare())
        assertFalse("segundo toque em REC não pode preparar de novo", m.tryBeginPrepare())
        assertSame(RecState.Preparing, m.current)
    }

    @Test
    fun cannotStartWithoutPreparing_orStopWithoutRecording() {
        val m = RecStateMachine()
        assertFalse(m.tryStart())
        assertFalse(m.tryBeginStop())
        assertFalse(m.finishStop())
        assertFalse(m.abortPrepare())
        assertSame(RecState.Idle, m.current)
    }

    @Test
    fun cannotPrepareWhileRecordingOrStopping() {
        val m = RecStateMachine()
        m.tryBeginPrepare()
        m.tryStart()
        assertFalse(m.tryBeginPrepare())
        m.tryBeginStop()
        assertFalse(m.tryBeginPrepare())
        assertFalse("não dá para parar duas vezes", m.tryBeginStop())
    }

    @Test
    fun failedPrepare_returnsToIdle() {
        val m = RecStateMachine()
        m.tryBeginPrepare()
        assertTrue(m.abortPrepare())
        assertSame(RecState.Idle, m.current)
        assertTrue("novo take possível após falha", m.tryBeginPrepare())
    }

    @Test
    fun abortPrepare_doesNotAffectRunningTake() {
        val m = RecStateMachine()
        m.tryBeginPrepare()
        m.tryStart()
        assertFalse(m.abortPrepare())
        assertSame(RecState.Recording, m.current)
    }

    @Test
    fun forceIdle_resetsFromAnyState() {
        val m = RecStateMachine()
        m.tryBeginPrepare()
        m.tryStart()
        m.tryBeginStop()
        m.forceIdle()
        assertSame(RecState.Idle, m.current)
    }

    @Test
    fun stateFlowReflectsTransitions() {
        val m = RecStateMachine()
        m.tryBeginPrepare()
        assertSame(RecState.Preparing, m.state.value)
    }

    @Test
    fun isTransitioning_onlyForPreparingAndStopping() {
        assertFalse(RecState.Idle.isTransitioning)
        assertTrue(RecState.Preparing.isTransitioning)
        assertFalse(RecState.Recording.isTransitioning)
        assertTrue(RecState.Stopping.isTransitioning)
    }

    @Test
    fun concurrentPrepare_onlyOneThreadWins() {
        val m = RecStateMachine()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val winners = AtomicInteger(0)
        repeat(threads) {
            pool.execute {
                ready.countDown()
                go.await()
                if (m.tryBeginPrepare()) winners.incrementAndGet()
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, winners.get())
    }
}
