package com.bragastudio.mobile.coremedia.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionPolicyTest {

    private val none = SessionConsumers()
    private val rec = SessionConsumers(recording = true)
    private val ndi = SessionConsumers(ndi = true)
    private val bsp = SessionConsumers(bsp = true)

    @Test
    fun consumidores_contam_referencias() {
        assertEquals(0, none.count)
        assertEquals(1, rec.count)
        assertEquals(3, SessionConsumers(true, true, true).count)
        assertFalse(none.holdsSession)
        assertTrue(rec.holdsSession)
        assertFalse(ndi.holdsSession)
        assertFalse(bsp.holdsSession)
    }

    @Test
    fun recState_diferente_de_idle_conta_como_gravando() {
        assertFalse(SessionConsumers.from(RecState.Idle, true, true).holdsSession)
        assertTrue(SessionConsumers.from(RecState.Preparing, false, false).recording)
        assertTrue(SessionConsumers.from(RecState.Recording, false, false).recording)
        // Stopping ainda protege a finalização do arquivo.
        assertTrue(SessionConsumers.from(RecState.Stopping, false, false).recording)
    }

    @Test
    fun detach_com_rec_solta_so_o_preview() {
        assertEquals(DetachAction.RELEASE_PREVIEW_ONLY, CaptureSessionPolicy.detachAction(rec))
    }

    @Test
    fun detach_sem_rec_desliga_tudo_mesmo_com_ndi_ou_bsp() {
        assertEquals(DetachAction.FULL_SHUTDOWN, CaptureSessionPolicy.detachAction(none))
        assertEquals(DetachAction.FULL_SHUTDOWN, CaptureSessionPolicy.detachAction(ndi))
        assertEquals(DetachAction.FULL_SHUTDOWN, CaptureSessionPolicy.detachAction(bsp))
    }

    @Test
    fun desligamento_so_quando_ninguem_usa_a_sessao() {
        // sessão viva, sem preview e sem consumidor: desliga
        assertTrue(CaptureSessionPolicy.shouldShutdownSession(none, previewAttached = false, sessionLive = true))
        // preview anexado: não desliga
        assertFalse(CaptureSessionPolicy.shouldShutdownSession(none, previewAttached = true, sessionLive = true))
        // REC ativo: não desliga
        assertFalse(CaptureSessionPolicy.shouldShutdownSession(rec, previewAttached = false, sessionLive = true))
        // NDI/BSP não seguram a sessão sem preview
        assertTrue(CaptureSessionPolicy.shouldShutdownSession(ndi, previewAttached = false, sessionLive = true))
        assertTrue(CaptureSessionPolicy.shouldShutdownSession(bsp, previewAttached = false, sessionLive = true))
        // nada de pé: nada a desligar
        assertFalse(CaptureSessionPolicy.shouldShutdownSession(none, previewAttached = false, sessionLive = false))
    }

    @Test
    fun fgs_nao_sobe_sem_rec_nem_com_ndi_ou_bsp() {
        for (c in listOf(none, ndi, bsp, SessionConsumers(ndi = true, bsp = true))) {
            val plan = CaptureSessionPolicy.planForeground(c, hasCameraPermission = true, hasMicPermission = true)
            assertFalse(plan.start)
            assertNotNull(plan.reason)
        }
    }

    @Test
    fun fgs_nao_sobe_sem_nenhuma_permissao() {
        val plan = CaptureSessionPolicy.planForeground(rec, hasCameraPermission = false, hasMicPermission = false)
        assertFalse(plan.start)
    }

    @Test
    fun fgs_sobe_com_os_dois_tipos_quando_ha_as_duas_permissoes() {
        val plan = CaptureSessionPolicy.planForeground(rec, hasCameraPermission = true, hasMicPermission = true)
        assertTrue(plan.start)
        assertTrue(plan.camera)
        assertTrue(plan.microphone)
    }

    @Test
    fun fgs_omite_o_tipo_sem_permissao() {
        val semMic = CaptureSessionPolicy.planForeground(rec, hasCameraPermission = true, hasMicPermission = false)
        assertTrue(semMic.start && semMic.camera && !semMic.microphone)
        val semCam = CaptureSessionPolicy.planForeground(rec, hasCameraPermission = false, hasMicPermission = true)
        assertTrue(semCam.start && !semCam.camera && semCam.microphone)
    }

    @Test
    fun titulos_da_notificacao() {
        assertEquals("Gravando — BDSM", CaptureSessionPolicy.notificationTitle(rec))
        assertEquals("Gravando e transmitindo — BDSM", CaptureSessionPolicy.notificationTitle(SessionConsumers(recording = true, ndi = true)))
    }
}
