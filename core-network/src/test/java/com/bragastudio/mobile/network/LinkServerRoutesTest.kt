package com.bragastudio.mobile.network

import android.content.Context
import com.bragastudio.mobile.network.auth.FakeAuthStore
import com.bragastudio.mobile.network.auth.LinkAuthManager
import com.bragastudio.mobile.network.service.TransferNotifier
import com.bragastudio.mobile.network.sharing.DeviceInfoResponse
import com.bragastudio.mobile.network.sharing.DeviceInfoService
import com.bragastudio.mobile.network.sharing.LutLibraryService
import com.bragastudio.mobile.network.sharing.MediaLibraryService
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Rotas do Link sobre `testApplication` (sem Netty): 401 sem token, 400 em path inválido,
 * 413 em corpos grandes, pareamento e downloads com Range.
 */
class LinkServerRoutesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private lateinit var auth: LinkAuthManager
    private lateinit var media: MediaLibraryService
    private lateinit var notifier: TransferNotifier
    private lateinit var telemetry: LinkTelemetry
    private lateinit var luts: LutLibraryService
    private lateinit var deps: LinkModuleDeps

    @Before
    fun setUp() {
        mockAndroidLog()
        auth = LinkAuthManager(FakeAuthStore(), { now }, SecureRandom())
        media = mockk()
        notifier = mockk(relaxed = true)
        telemetry = LinkTelemetry()

        val context = mockk<Context>()
        every { context.getExternalFilesDir(null) } returns tmp.newFolder("ext")
        luts = LutLibraryService(context)

        val deviceInfo = mockk<DeviceInfoService>()
        every { deviceInfo.getDeviceInfo() } returns DeviceInfoResponse(
            deviceName = "Teste", deviceModel = "Modelo", appVersion = "1.0",
            batteryLevel = 80, totalStorageBytes = 100, freeStorageBytes = 50, isCharging = false,
        )
        coEvery { media.getMediaList() } returns emptyList()

        deps = LinkModuleDeps(
            auth = auth,
            deviceInfo = deviceInfo,
            media = media,
            luts = luts,
            notifier = notifier,
            telemetry = telemetry,
            currentState = { LinkState() },
            wsClients = MutableStateFlow(0),
            lutMaxUploadBytes = 1024,
        )
    }

    @After
    fun tearDown() {
        unmockAndroidLog()
    }

    private fun withServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { linkModule(deps) }
        block()
    }

    /** Pareia um cliente e devolve o token. */
    private fun pairClient(address: String = "localhost"): String {
        val req = auth.createRequest("cli", "Cliente", address)!!
        auth.approve(req.id)
        return auth.pollRequest(req.id)!!.token!!
    }

    // ---- 401 --------------------------------------------------------------------------

    @Test
    fun `rotas protegidas retornam 401 sem token ou com token invalido`() = withServer {
        for (path in listOf("/api/media", "/api/luts", "/api/media/x/download", "/api/media/x/thumbnail")) {
            assertEquals(path, HttpStatusCode.Unauthorized, client.get(path).status)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/media/x").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/luts/a.cube").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/luts/upload").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/media") {
                header(HttpHeaders.Authorization, "Bearer token-errado")
            }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/media?token=token-errado").status)
    }

    @Test
    fun `token valido por header ou query libera a rota`() = withServer {
        val token = pairClient()
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/media") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.status,
        )
        assertEquals(HttpStatusCode.OK, client.get("/api/media?token=$token").status)
    }

    @Test
    fun `token revogado volta a ser 401`() = withServer {
        val token = pairClient()
        assertEquals(HttpStatusCode.OK, client.get("/api/media?token=$token").status)
        auth.revoke("cli")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/media?token=$token").status)
    }

    @Test
    fun `dashboard e discovery sao publicos mas discovery sem token e minimo`() = withServer {
        assertEquals(HttpStatusCode.OK, client.get("/").status)

        val minimal = client.get("/api/discovery/info")
        assertEquals(HttpStatusCode.OK, minimal.status)
        assertTrue(minimal.headers[HttpHeaders.ContentType]!!.startsWith(ContentType.Application.Json.toString()))
        val text = minimal.bodyAsText()
        assertTrue(text.contains("authRequired"))
        assertTrue("sem token nao pode vazar bateria/armazenamento", !text.contains("batteryLevel") && !text.contains("StorageBytes"))

        val token = pairClient()
        val full = client.get("/api/discovery/info?token=$token").bodyAsText()
        assertTrue(full.contains("batteryLevel"))
    }

    // ---- 400 / 404 ---------------------------------------------------------------------

    @Test
    fun `path de LUT invalido retorna 400`() = withServer {
        val token = pairClient()
        suspend fun del(path: String) = client.delete("/api/luts/$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.status
        assertEquals(HttpStatusCode.BadRequest, del("notacube.txt"))
        assertEquals(HttpStatusCode.BadRequest, del("a%5Cb.cube")) // barra invertida
        assertEquals(HttpStatusCode.BadRequest, del("a/b/c/d/e.cube")) // profundidade
        // caminho valido que nao existe: 404, nao 400
        assertEquals(HttpStatusCode.NotFound, del("nao-existe.cube"))
    }

    @Test
    fun `upload com relativePath invalido retorna 400`() = withServer {
        val token = pairClient()
        val r = client.post("/api/luts/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("relativePath", "../evil.cube")
                        append(
                            "file", "LUT_3D_SIZE 2".toByteArray(),
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"evil.cube\"")
                            },
                        )
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `upload sem arquivo retorna 400`() = withServer {
        val token = pairClient()
        val r = client.post("/api/luts/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData { append("relativePath", "a.cube") }))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `upload valido grava e notifica`() = withServer {
        val token = pairClient()
        val r = client.post("/api/luts/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("relativePath", "cinema/a.cube")
                        append(
                            "file", "LUT_3D_SIZE 2".toByteArray(),
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"a.cube\"")
                            },
                        )
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("cinema/a.cube", luts.getLutsList().single().relativePath)
        verify { notifier.notifyLutsSynced(1) }
    }

    // ---- 413 ---------------------------------------------------------------------------

    @Test
    fun `upload acima do limite retorna 413`() = withServer {
        val token = pairClient()
        val r = client.post("/api/luts/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("relativePath", "grande.cube")
                        append(
                            "file", ByteArray(4096),
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"grande.cube\"")
                            },
                        )
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertTrue(luts.getLutsList().isEmpty())
    }

    @Test
    fun `pair request grande demais retorna 413`() = withServer {
        val r = client.post("/api/pair/request") {
            contentType(ContentType.Application.Json)
            setBody("{\"clientId\":\"x\",\"clientName\":\"" + "n".repeat(3000) + "\"}")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }

    // ---- Pareamento --------------------------------------------------------------------

    @Test
    fun `pair request invalido retorna 400 e pedido duplicado 429`() = withServer {
        val bad = client.post("/api/pair/request") {
            contentType(ContentType.Application.Json)
            setBody("isto nao e json")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val body = "{\"clientId\":\"abc\",\"clientName\":\"OBS\"}"
        val first = client.post("/api/pair/request") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val second = client.post("/api/pair/request") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }

    @Test
    fun `fluxo completo de pareamento entrega o token uma vez`() = withServer {
        val resp = client.post("/api/pair/request") {
            contentType(ContentType.Application.Json)
            setBody("{\"clientId\":\"abc\",\"clientName\":\"OBS\"}")
        }
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val id = obj["requestId"]!!.jsonPrimitive.content
        assertEquals(4, obj["code"]!!.jsonPrimitive.content.length)

        val pending = kotlinx.serialization.json.Json.parseToJsonElement(client.get("/api/pair/status/$id").bodyAsText()).jsonObject
        assertEquals("PENDING", pending["state"]!!.jsonPrimitive.content)
        assertTrue(!pending.containsKey("token"))

        auth.approve(id)
        val approved = kotlinx.serialization.json.Json.parseToJsonElement(client.get("/api/pair/status/$id").bodyAsText()).jsonObject
        assertEquals("APPROVED", approved["state"]!!.jsonPrimitive.content)
        val token = approved["token"]!!.jsonPrimitive.content

        val again = kotlinx.serialization.json.Json.parseToJsonElement(client.get("/api/pair/status/$id").bodyAsText()).jsonObject
        assertTrue("o token so pode ser lido uma vez", !again.containsKey("token"))

        assertEquals(HttpStatusCode.OK, client.get("/api/media?token=$token").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/pair/status/inexistente").status)
    }

    // ---- Downloads com Range -------------------------------------------------------------

    private fun fakeVideo(): File = tmp.newFile("take.mp4").also { f ->
        f.writeBytes(ByteArray(100) { it.toByte() })
        coEvery { media.getMediaFile("rec1") } returns f
        coEvery { media.getMediaFile("naoexiste") } returns null
    }

    @Test
    fun `download completo responde 200 com Accept-Ranges e notifica ao final`() = withServer {
        val file = fakeVideo()
        val token = pairClient()
        val r = client.get("/api/media/rec1/download?token=$token")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("bytes", r.headers[HttpHeaders.AcceptRanges])
        assertEquals(file.readBytes().toList(), r.body<ByteArray>().toList())
        verify(exactly = 1) { notifier.notifyRecordingsCopied("take.mp4") }
    }

    @Test
    fun `download parcial responde 206 e so notifica quando chega ao fim`() = withServer {
        val file = fakeVideo()
        val token = pairClient()

        val meio = client.get("/api/media/rec1/download?token=$token") { header(HttpHeaders.Range, "bytes=10-19") }
        assertEquals(HttpStatusCode.PartialContent, meio.status)
        assertEquals("bytes 10-19/100", meio.headers[HttpHeaders.ContentRange])
        assertEquals(file.readBytes().copyOfRange(10, 20).toList(), meio.body<ByteArray>().toList())
        verify(exactly = 0) { notifier.notifyRecordingsCopied(any()) }

        // retomada ate o ultimo byte: notifica uma vez
        val fim = client.get("/api/media/rec1/download?token=$token") { header(HttpHeaders.Range, "bytes=90-") }
        assertEquals(HttpStatusCode.PartialContent, fim.status)
        assertEquals("bytes 90-99/100", fim.headers[HttpHeaders.ContentRange])
        assertEquals(10, fim.body<ByteArray>().size)
        verify(exactly = 1) { notifier.notifyRecordingsCopied("take.mp4") }
    }

    @Test
    fun `range fora do arquivo retorna 416 e id inexistente 404`() = withServer {
        fakeVideo()
        val token = pairClient()
        val r = client.get("/api/media/rec1/download?token=$token") { header(HttpHeaders.Range, "bytes=500-600") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, r.status)
        assertEquals("bytes */100", r.headers[HttpHeaders.ContentRange])
        assertEquals(HttpStatusCode.NotFound, client.get("/api/media/naoexiste/download?token=$token").status)
        verify(exactly = 0) { notifier.notifyRecordingsCopied(any()) }
    }
}
