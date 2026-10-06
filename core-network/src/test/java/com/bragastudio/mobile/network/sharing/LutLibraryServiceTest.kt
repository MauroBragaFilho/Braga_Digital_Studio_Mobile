package com.bragastudio.mobile.network.sharing

import android.content.Context
import com.bragastudio.mobile.network.mockAndroidLog
import com.bragastudio.mobile.network.unmockAndroidLog
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LutLibraryServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var base: File
    private lateinit var service: LutLibraryService

    @Before
    fun setUp() {
        mockAndroidLog()
        base = tmp.newFolder("external")
        val context = mockk<Context>()
        every { context.getExternalFilesDir(null) } returns base
        service = LutLibraryService(context)
    }

    @After
    fun tearDown() {
        unmockAndroidLog()
    }

    private fun lutsRoot() = File(base, "luts").canonicalFile

    // ---- resolveSafe -------------------------------------------------------------------

    @Test
    fun `aceita caminhos validos dentro de luts`() {
        val f = service.resolveSafe("look.cube")
        assertNotNull(f)
        assertEquals(File(lutsRoot(), "look.cube"), f)
        assertNotNull(service.resolveSafe("cinema/film/look.CUBE"))
        assertNotNull(service.resolveSafe("a/b/c/d.cube")) // 4 segmentos e o maximo
    }

    @Test
    fun `rejeita travessia de diretorio e caminhos absolutos`() {
        assertNull(service.resolveSafe("../evil.cube"))
        assertNull(service.resolveSafe("a/../../evil.cube"))
        assertNull(service.resolveSafe("a/./b.cube"))
        assertNull(service.resolveSafe("/etc/passwd.cube"))
        assertNull(service.resolveSafe("//evil.cube"))
        assertNull(service.resolveSafe("a\\b.cube"))
        assertNull(service.resolveSafe("..\\evil.cube"))
    }

    @Test
    fun `rejeita vazio segmentos vazios controle e tamanho`() {
        assertNull(service.resolveSafe(""))
        assertNull(service.resolveSafe("   "))
        assertNull(service.resolveSafe("a//b.cube"))
        assertNull(service.resolveSafe("a/b.cube/"))
        assertNull(service.resolveSafe("a\u0000.cube"))
        assertNull(service.resolveSafe("a\n.cube"))
        assertNull(service.resolveSafe("a/b/c/d/e.cube")) // 5 segmentos
        assertNull(service.resolveSafe("x".repeat(256) + ".cube"))
    }

    @Test
    fun `rejeita extensao diferente de cube`() {
        assertNull(service.resolveSafe("look.txt"))
        assertNull(service.resolveSafe("look"))
        assertNull(service.resolveSafe("look.cube.exe"))
        assertNull(service.resolveSafe(".cube.bak"))
    }

    @Test
    fun `symlink para fora da raiz e rejeitado`() {
        val outside = tmp.newFolder("outside")
        val root = File(base, "luts").apply { mkdirs() }
        val link = File(root, "link")
        val created = try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (e: Exception) {
            false // sem permissao para symlink (ex.: Windows sem privilegio): nada a verificar
        }
        if (created) {
            assertNull(service.resolveSafe("link/evil.cube"))
        }
    }

    // ---- save / delete / changes ---------------------------------------------------------

    @Test
    fun `salvar cria subpastas lista e detecta conflito`() {
        assertTrue(service.saveLut("cinema/look.cube", "LUT_3D_SIZE 2".toByteArray()))
        val list = service.getLutsList()
        assertEquals(1, list.size)
        assertEquals("cinema/look.cube", list[0].relativePath)
        assertEquals("look.cube", list[0].name)

        // mesmo conteudo: sem conflito; conteudo diferente: conflito
        assertNull(service.checkConflict("cinema/look.cube", list[0].hash))
        val conflict = service.checkConflict("cinema/look.cube", "outrohash")
        assertNotNull(conflict)
        assertEquals(list[0].hash, conflict!!.existingHash)
        // nao deixa arquivo temporario para tras
        assertFalse(File(lutsRoot(), "cinema/look.cube.part").exists())
    }

    @Test
    fun `excluir remove arquivo e pastas vazias mas nunca a raiz`() {
        service.saveLut("cinema/look.cube", ByteArray(4))
        assertTrue(service.deleteLut("cinema/look.cube"))
        assertFalse(File(lutsRoot(), "cinema").exists())
        assertTrue(lutsRoot().exists())
        assertFalse(service.deleteLut("cinema/look.cube")) // ja nao existe
        assertFalse(service.deleteLut("../fora.cube"))
    }

    @Test
    fun `changes emite apos upload e exclusao bem-sucedidos e nao em falhas`() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2000) { service.changes.first() } }
        assertTrue(service.saveLut("a.cube", ByteArray(2)))
        first.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2000) { service.changes.first() } }
        assertTrue(service.deleteLut("a.cube"))
        second.await()

        // falha (caminho invalido): nenhuma emissao
        val none = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                withTimeout(300) {
                    service.changes.first()
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
        assertFalse(service.saveLut("../x.cube", ByteArray(1)))
        assertFalse(service.deleteLut("nao-existe.cube"))
        assertFalse(none.await())
    }
}
