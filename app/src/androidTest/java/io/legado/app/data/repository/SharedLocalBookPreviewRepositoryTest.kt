package io.legado.app.data.repository

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class SharedLocalBookPreviewRepositoryTest {
    private lateinit var root: File
    private val repository = FileSharedLocalBookPreviewRepository()
    @Before fun setup() { root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "shared-preview-${UUID.randomUUID()}").apply { mkdirs() } }
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun actualFileProjectionKeepsPreviewTitleAuthorSupportedFormatSizeDateAndOrderWithoutTouchingInputs() = runBlocking {
        val txt = File(root, "FIRST.TXT").apply { writeText("Original text"); setLastModified(1700000000000) }
        val pdf = File(root, "second.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)); setLastModified(1700000000000) }
        val seeds = listOf(SharedLocalBookPreviewSeed(Uri.fromFile(txt).toString(), "Preview name / Author"), SharedLocalBookPreviewSeed(Uri.fromFile(pdf).toString(), null))
        val rows = repository.project(seeds)
        assertEquals(listOf("Preview name / Author", "second.pdf"), rows.map { it.title }); assertEquals(listOf("TXT", "pdf"), rows.map { it.format })
        assertTrue(rows.all { it.selectable && it.size.isNotBlank() && it.date.isNotBlank() }); assertEquals("Original text", txt.readText()); assertArrayEquals(byteArrayOf(1, 2, 3), pdf.readBytes())
        txt.appendText(" Additional bytes")
        assertNotEquals(rows.first().size, repository.project(seeds).first().size)
    }
    @Test fun duplicateUriIsOneStableRowAndDirectoryExistingShelfRowsAreNotSelectable() = runBlocking {
        val directory = File(root, "sub").apply { mkdir() }; val file = File(root, "file.epub").apply { writeText("Data") }
        val uri = Uri.fromFile(file).toString()
        val rows = repository.project(listOf(SharedLocalBookPreviewSeed(uri, "Existing", true), SharedLocalBookPreviewSeed(uri, "Duplicate"), SharedLocalBookPreviewSeed(Uri.fromFile(directory).toString(), "Folder", directory = true)))
        assertEquals(2, rows.size); assertFalse(rows.first().selectable); assertFalse(rows.last().selectable); assertTrue(rows.last().directory)
        assertEquals(FileSharedLocalBookPreviewRepository.id(uri), rows.first().id)
    }
}
