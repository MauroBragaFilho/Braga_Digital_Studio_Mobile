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
        assertFalse(none.anyActive)
        assertTrue(ndi.anyActive)
    }

    @Test
    fun recState_diferente_de_idle_conta_como_gravando() {
        assertFalse(SessionConsumers.from(RecState.Idle, false, false).anyActive)
        assertTrue(SessionConsumers.from(RecState.Preparing, false, false).recording)
        assertTrue(SessionConsumers.from(RecState.Recording, false, false).recording)
        // Stopping ainda protege a finalização do arquivo.
        assertTrue(SessionConsumers.from(RecState.Stopping, false, false).recording)
    }

    @Test
    fun detach_com_saida_ativa_solta_so_o_preview() {
        assertEquals(DetachAction.RELEASE_PREVIEW_ONLY, CaptureSessionPolicy.detachAction(rec))
        assertEquals(DetachAction.RELEASE_PREVIEW_ONLY, CaptureSessionPolicy.detachAction(ndi))
        assertEquals(DetachAction.RELEASE_PREVIEW_ONLY, CaptureSessionPolicy.detachAction(bsp))
    }

    @Test
    fun detach_sem_saida_ativa_desliga_tudo() {
        assertEquals(DetachAction.FULL_SHUTDOWN, CaptureSessionPolicy.detachAction(none))
    }

    @Test
    fun desligamento_so_quando_ninguem_usa_a_sessao() {
        // sessão viva, sem preview e sem consumidor: desliga
        assertTrue(CaptureSessionPolicy.shouldShutdownSession(none, previewAttached = false, sessionLive = true))
        // preview anexado: não desliga
        assertFalse(CaptureSessionPolicy.shouldShutdownSession(none, previewAttached = true, sessionLive = true))
        // consumidor ativo: não desliga
        assertFalse(CaptureSessionPolicy.shouldShutdownSession(rec, previewAttached = false, sessionLive = true))
        // nada de pé: nada a desligar
        assertFalse(CaptureSessionPolicy.shouldShutdownSession(none, previewAttached = false, sessionLive = false))
    }

    @Test
    fun fgs_nao_sobe_sem_consumidor() {
        val plan = CaptureSessionPolicy.planForeground(none, hasCameraPermission = true, hasMicPermission = true)
        assertFalse(plan.start)
        assertNotNull(plan.reason)
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
        val semMic = CaptureSessionPolicy.planForeground(ndi, hasCameraPermission = true, hasMicPermission = false)
        assertTrue(semMic.start && semMic.camera && !semMic.microphone)
        val semCam = CaptureSessionPolicy.planForeground(ndi, hasCameraPermission = false, hasMicPermission = true)
        assertTrue(semCam.start && !semCam.camera && semCam.microphone)
    }

    @Test
    fun audio_necessario_para_rec_ndi_com_audio_e_preview() {
        assertTrue(CaptureSessionPolicy.audioNeeded(rec, previewAttached = false, ndiAudioEnabled = false))
        assertTrue(CaptureSessionPolicy.audioNeeded(ndi, previewAttached = false, ndiAudioEnabled = true))
        assertFalse(CaptureSessionPolicy.audioNeeded(ndi, previewAttached = false, ndiAudioEnabled = false))
        assertTrue(CaptureSessionPolicy.audioNeeded(none, previewAttached = true, ndiAudioEnabled = false))
        assertFalse(CaptureSessionPolicy.audioNeeded(none, previewAttached = false, ndiAudioEnabled = true))
        // BSP não leva áudio
        assertFalse(CaptureSessionPolicy.audioNeeded(bsp, previewAttached = false, ndiAudioEnabled = true))
    }

    @Test
    fun titulos_da_notificacao() {
        assertEquals("Gravando — BDSM", CaptureSessionPolicy.notificationTitle(rec))
        assertEquals("Transmitindo (NDI) — BDSM", CaptureSessionPolicy.notificationTitle(ndi))
        assertEquals("Transmitindo (BSP) — BDSM", CaptureSessionPolicy.notificationTitle(bsp))
        assertEquals("Transmitindo (NDI e BSP) — BDSM", CaptureSessionPolicy.notificationTitle(SessionConsumers(ndi = true, bsp = true)))
        assertEquals("Gravando e transmitindo — BDSM", CaptureSessionPolicy.notificationTitle(SessionConsumers(recording = true, ndi = true)))
    }
}
