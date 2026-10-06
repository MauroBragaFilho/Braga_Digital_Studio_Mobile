package com.bragastudio.mobile.network

import com.bragastudio.mobile.core.database.RecordingEntity
import com.bragastudio.mobile.core.repository.RecordingRepository
import com.bragastudio.mobile.network.sharing.MediaLibraryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinkTelemetryAndMediaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- M25: encodeDefaults ---------------------------------------------------------------

    @Test
    fun `LinkState serializa todos os campos mesmo com valores padrao`() {
        val text = LinkJson.instance.encodeToString(LinkState())
        val obj = LinkJson.instance.parseToJsonElement(text).jsonObject
        for (key in listOf(
            "deviceName", "batteryLevel", "isCharging", "captureSource", "cameraLens",
            "fps", "microphone", "isRecording", "ndiStreamName", "tally",
        )) {
            assertTrue("campo ausente: $key", obj.containsKey(key))
        }
        assertEquals("false", obj["isRecording"]!!.jsonPrimitive.content)
        assertEquals("OFF", obj["tally"]!!.jsonPrimitive.content)
    }

    // ---- M52: tally pelo WebSocket ---------------------------------------------------------------

    private fun deps(telemetry: LinkTelemetry) = LinkModuleDeps(
        auth = mockk(), deviceInfo = mockk(), media = mockk(), luts = mockk(), notifier = mockk(),
        telemetry = telemetry, currentState = { LinkState() }, wsClients = MutableStateFlow(0),
    )

    @Test
    fun `TALLY_UPDATE valido atualiza o tally`() {
        val t = LinkTelemetry()
        val d = deps(t)
        handleClientMessage(d, """{"type":"TALLY_UPDATE","state":"PROGRAM"}""")
        assertEquals(TallyState.PROGRAM, t.tally.value)
        handleClientMessage(d, """{"type":"TALLY_UPDATE","state":"preview"}""")
        assertEquals(TallyState.PREVIEW, t.tally.value)
        handleClientMessage(d, """{"type":"TALLY_UPDATE","state":"OFF"}""")
        assertEquals(TallyState.OFF, t.tally.value)
    }

    @Test
    fun `mensagens invalidas sao ignoradas`() {
        val t = LinkTelemetry()
        t.setTally(TallyState.PREVIEW)
        val d = deps(t)
        handleClientMessage(d, "isto nao e json")
        handleClientMessage(d, """{"type":"OUTRA","state":"PROGRAM"}""")
        handleClientMessage(d, """{"type":"TALLY_UPDATE","state":"VERMELHO"}""")
        handleClientMessage(d, """{"type":"TALLY_UPDATE"}""")
        handleClientMessage(d, """[1,2,3]""")
        handleClientMessage(d, """{"type":"TALLY_UPDATE","state":"PROGRAM","x":"${"a".repeat(2000)}"}""")
        assertEquals(TallyState.PREVIEW, t.tally.value)
    }

    // ---- L4 / B58: status e exclusao -------------------------------------------------------------

    private fun entity(id: String, status: String, path: String = "", thumb: String = "") = RecordingEntity(
        id = id, fileName = "$id.mp4", filePath = path, thumbnailPath = thumb, durationMs = 1000,
        sizeBytes = 10, resolution = "1920x1080", frameRate = 30, codec = "H264", bitrate = 1,
        audioCodec = "AAC", audioSampleRate = 48000, createdAt = 0L, status = status,
    )

    @Test
    fun `lista e downloads so expoem gravacoes concluidas`() = runBlocking {
        val file = tmp.newFile("ok.mp4")
        val repo = mockk<RecordingRepository>()
        coEvery { repo.getAllRecordings() } returns flowOf(
            listOf(
                entity("a", "COMPLETED", file.path),
                entity("b", "IN_PROGRESS", file.path),
                entity("c", "CORRUPTED", file.path),
                entity("d", "DELETED", file.path),
            ),
        )
        coEvery { repo.getRecordingById("a") } returns entity("a", "COMPLETED", file.path)
        coEvery { repo.getRecordingById("b") } returns entity("b", "IN_PROGRESS", file.path)
        coEvery { repo.getRecordingById("c") } returns entity("c", "CORRUPTED", file.path)
        val service = MediaLibraryService(repo)

        assertEquals(listOf("a"), service.getMediaList().map { it.id })
        assertNotNull(service.getMediaFile("a"))
        assertNull(service.getMediaFile("b"))
        assertNull(service.getMediaFile("c"))
        assertNull(service.getThumbnailFile("b"))
    }

    @Test
    fun `deleteMedia remove arquivo e linha`() = runBlocking {
        val file = tmp.newFile("take.mp4")
        val thumb = tmp.newFile("take.jpg")
        val repo = mockk<RecordingRepository>(relaxed = true)
        coEvery { repo.getRecordingById("a") } returns entity("a", "COMPLETED", file.path, thumb.path)

        assertTrue(MediaLibraryService(repo).deleteMedia("a"))
        assertFalse(file.exists())
        assertFalse(thumb.exists())
        coVerify(exactly = 1) { repo.deleteRecording("a") }
    }

    @Test
    fun `deleteMedia mantem a linha se o arquivo nao puder ser apagado`() = runBlocking {
        // Um diretorio nao vazio no lugar do arquivo: File.delete() falha e o arquivo continua existindo.
        val dir = tmp.newFolder("naoapagavel")
        java.io.File(dir, "filho.txt").writeText("x")
        val repo = mockk<RecordingRepository>(relaxed = true)
        coEvery { repo.getRecordingById("a") } returns entity("a", "COMPLETED", dir.path)

        assertFalse(MediaLibraryService(repo).deleteMedia("a"))
        assertTrue(dir.exists())
        coVerify(exactly = 0) { repo.deleteRecording(any()) }
    }

    @Test
    fun `deleteMedia nao apaga gravacao em andamento nem id inexistente`() = runBlocking {
        val file = tmp.newFile("rec.mp4")
        val repo = mockk<RecordingRepository>(relaxed = true)
        coEvery { repo.getRecordingById("a") } returns entity("a", "IN_PROGRESS", file.path)
        coEvery { repo.getRecordingById("zz") } returns null
        val service = MediaLibraryService(repo)

        assertFalse(service.deleteMedia("a"))
        assertTrue(file.exists())
        assertFalse(service.deleteMedia("zz"))
        coVerify(exactly = 0) { repo.deleteRecording(any()) }
    }
}
