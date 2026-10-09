package com.bragastudio.mobile.coremedia.bsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacedPacketQueueTest {

    private val frameNs = 33_333_333L // 30 fps
    private fun queue(slots: Int = 64, maxLagNs: Long = frameNs * 2) = PacedPacketQueue(slots, 64, maxLagNs)

    private fun pkt(id: Int, size: Int = 20) = ByteArray(size) { id.toByte() }

    /** Enfileira um quadro de [n] pacotes; o primeiro byte de cada pacote é o [firstId] + índice. */
    private fun PacedPacketQueue.frame(n: Int, key: Boolean, now: Long, firstId: Int = 0) {
        beginFrame(key)
        for (i in 0 until n) send(pkt(firstId + i), 20)
        endFrame(now, frameNs)
    }

    private fun PacedPacketQueue.drainAll(): List<Int> {
        val out = ArrayList<Int>()
        val dest = ByteArray(64)
        while (true) {
            val n = pollDue(Long.MAX_VALUE - 1, dest)
            if (n < 0) break
            out += dest[0].toInt()
        }
        return out
    }

    @Test
    fun firstPacketIsImmediateAndTheRestSpreadWithinOneThirdOfTheFrame() {
        val q = queue()
        q.frame(10, key = false, now = 1_000)
        val dest = ByteArray(64)
        val due = ArrayList<Long>()
        // descobre o horário de cada pacote avançando o relógio até ele vencer
        var now = 1_000L
        while (q.pending > 0) {
            val next = q.nextDueNs()
            assertTrue(next >= now)
            now = next
            assertEquals(20, q.pollDue(now, dest))
            due += now
        }
        assertEquals(10, due.size)
        assertEquals("o primeiro sai já", 1_000L, due.first())
        val spread = due.last() - due.first()
        assertEquals("o último sai em 1/3 do intervalo", frameNs / 3, spread)
        assertTrue("saída espaçada, não em rajada", due.zipWithNext().all { (a, b) -> b > a })
        assertTrue("nunca passa de 1/3 do quadro", spread <= frameNs / 3)
    }

    @Test
    fun packetsLeaveInFifoOrderAcrossFrames() {
        val q = queue()
        q.frame(3, key = true, now = 0, firstId = 10)
        q.frame(2, key = false, now = frameNs, firstId = 20)
        assertEquals(listOf(10, 11, 12, 20, 21), q.drainAll())
    }

    @Test
    fun pollDueReturnsNothingBeforeTheScheduledTime() {
        val q = queue()
        q.frame(5, key = false, now = 1_000)
        val dest = ByteArray(64)
        assertEquals(20, q.pollDue(1_000, dest)) // o primeiro
        assertEquals("o segundo ainda não venceu", -1, q.pollDue(1_000, dest))
        assertTrue(q.nextDueNs() > 1_000)
    }

    @Test
    fun singlePacketFrameIsImmediate() {
        val q = queue()
        q.frame(1, key = false, now = 5_000)
        assertEquals(5_000L, q.nextDueNs())
    }

    @Test
    fun emptyQueueHasNoDueTime() {
        val q = queue()
        assertEquals(Long.MAX_VALUE, q.nextDueNs())
        assertEquals(-1, q.pollDue(Long.MAX_VALUE - 1, ByteArray(64)))
    }

    @Test
    fun packetsLargerThanASlotAreDroppedAndFlagOverflow() {
        val q = queue()
        q.beginFrame(false)
        q.send(ByteArray(65), 65)
        q.endFrame(0, frameNs)
        assertEquals(0, q.pending)
        assertEquals(1L, q.droppedPackets)
        assertTrue(q.consumeOverflow())
        assertFalse("o aviso é consumido", q.consumeOverflow())
    }

    @Test
    fun fullQueueDropsNonKeyFramesButKeepsTheKeyFrame() {
        val q = queue(slots = 8)
        q.frame(3, key = true, now = 0, firstId = 1) // IDR em espera
        q.frame(3, key = false, now = 0, firstId = 10) // P em espera
        // novo quadro P não cabe inteiro (só 2 slots livres): overflow
        q.frame(4, key = false, now = 0, firstId = 20)
        assertTrue(q.consumeOverflow())
        assertEquals("só o IDR sobrevive", listOf(1, 2, 3), q.drainAll())
        assertTrue(q.droppedPackets >= 4)
    }

    @Test
    fun stalledSenderTriggersDropOfNonKeyFramesAndIdrRequest() {
        val q = queue(slots = 256, maxLagNs = frameNs * 2)
        q.frame(2, key = true, now = 0, firstId = 1)
        // o consumidor travou: 3 quadros depois o mais antigo está atrasado mais de 2 quadros
        q.frame(2, key = false, now = frameNs, firstId = 10)
        q.frame(2, key = false, now = frameNs * 2, firstId = 20)
        assertFalse("ainda dentro do limite", q.consumeOverflow())
        q.frame(2, key = false, now = frameNs * 3, firstId = 30)
        assertTrue("passou de 2 quadros de atraso", q.consumeOverflow())
        assertEquals("só o IDR ficou na fila", listOf(1, 2), q.drainAll())
    }

    @Test
    fun queueNeverGrowsBeyondItsSlots() {
        val q = queue(slots = 16)
        repeat(100) { q.frame(5, key = false, now = it * frameNs) }
        assertTrue(q.pending <= 16)
    }

    @Test
    fun clearEmptiesTheQueueAndSlotsAreReusable() {
        val q = queue(slots = 8)
        q.frame(8, key = false, now = 0)
        assertEquals(8, q.pending)
        q.clear()
        assertEquals(0, q.pending)
        q.frame(8, key = false, now = 0)
        assertEquals(8, q.pending)
    }

    @Test
    fun slotsAreRecycledWithoutCorruption() {
        val q = queue(slots = 4)
        var id = 0
        repeat(50) {
            q.frame(3, key = false, now = it * frameNs, firstId = id)
            assertEquals(listOf(id, id + 1, id + 2).map { v -> v and 0x7F }, q.drainAll().map { v -> v and 0x7F })
            id += 3
        }
    }

    @Test
    fun audioStylePacketsAreImmediateAndNeverDroppedAsNonKey() {
        val q = PacedPacketQueue(8, 64, Long.MAX_VALUE / 4)
        repeat(5) {
            q.beginFrame(true)
            q.send(pkt(it), 20)
            q.endFrame(0, 0)
        }
        assertFalse(q.consumeOverflow())
        assertEquals(listOf(0, 1, 2, 3, 4), q.drainAll())
    }
}
