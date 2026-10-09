package com.bragastudio.mobile.coremedia.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLifecyclePolicyTest {

    private val bools = listOf(false, true)

    @Test
    fun shouldHoldCapture_so_monitor_ou_rec() {
        for (monitor in bools) {
            for (rec in bools) {
                assertEquals("m=$monitor r=$rec", monitor || rec, CaptureLifecyclePolicy.shouldHoldCapture(monitor, rec))
            }
        }
    }

    @Test
    fun sem_monitor_e_sem_rec_encerra() {
        assertEquals(CaptureLifecyclePolicy.Transition.CLOSE, CaptureLifecyclePolicy.transition(false, SessionConsumers()))
    }

    @Test
    fun saiu_do_monitor_com_ndi_ou_bsp_ligado_encerra() {
        assertEquals(CaptureLifecyclePolicy.Transition.CLOSE, CaptureLifecyclePolicy.transition(false, SessionConsumers(ndi = true)))
        assertEquals(CaptureLifecyclePolicy.Transition.CLOSE, CaptureLifecyclePolicy.transition(false, SessionConsumers(bsp = true)))
        assertEquals(CaptureLifecyclePolicy.Transition.CLOSE, CaptureLifecyclePolicy.transition(false, SessionConsumers(ndi = true, bsp = true)))
    }

    @Test
    fun saiu_do_monitor_com_rec_mantem_e_encerra_quando_rec_para() {
        assertEquals(CaptureLifecyclePolicy.Transition.KEEP, CaptureLifecyclePolicy.transition(false, SessionConsumers(recording = true)))
        assertEquals(CaptureLifecyclePolicy.Transition.KEEP, CaptureLifecyclePolicy.transition(false, SessionConsumers(recording = true, ndi = true)))
        // REC parou fora do Monitor (NDI continua "ligado"): encerra.
        assertEquals(CaptureLifecyclePolicy.Transition.CLOSE, CaptureLifecyclePolicy.transition(false, SessionConsumers(recording = false, ndi = true)))
    }

    @Test
    fun ndi_iniciado_na_tela_ndi_sem_monitor_nao_captura() {
        assertFalse(CaptureLifecyclePolicy.shouldHoldCapture(monitorVisible = false, consumers = SessionConsumers(ndi = true)))
        assertFalse(CaptureLifecyclePolicy.shouldRunForegroundService(sessionLive = false, recording = false))
        assertFalse(CaptureLifecyclePolicy.shouldCaptureAudio(sessionLive = false, monitorVisible = false, recording = false))
    }

    @Test
    fun monitor_visivel_mantem_com_ou_sem_ndi() {
        assertEquals(CaptureLifecyclePolicy.Transition.KEEP, CaptureLifecyclePolicy.transition(true, SessionConsumers()))
        assertEquals(CaptureLifecyclePolicy.Transition.KEEP, CaptureLifecyclePolicy.transition(true, SessionConsumers(ndi = true)))
    }

    @Test
    fun foreground_service_so_com_sessao_viva_e_rec() {
        for (live in bools) {
            for (rec in bools) {
                assertEquals(live && rec, CaptureLifecyclePolicy.shouldRunForegroundService(live, rec))
            }
        }
    }

    @Test
    fun microfone_so_com_sessao_viva_e_monitor_ou_rec() {
        for (live in bools) {
            for (monitor in bools) {
                for (rec in bools) {
                    assertEquals(live && (monitor || rec), CaptureLifecyclePolicy.shouldCaptureAudio(live, monitor, rec))
                }
            }
        }
    }

    @Test
    fun audio_do_ndi_so_enquanto_o_monitor_esta_visivel() {
        assertTrue(CaptureLifecyclePolicy.shouldCaptureAudio(sessionLive = true, monitorVisible = true, recording = false))
        assertFalse(CaptureLifecyclePolicy.shouldCaptureAudio(sessionLive = true, monitorVisible = false, recording = false))
    }
}
