package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalBookFileNameTest {

    @Test
    fun `file storage writes nested child and rejects traversal`() = withTempDirectory { root ->
        val booksDir = root.resolve("books").apply { mkdirs() }
        val outputFile = prepareLocalBookOutputFile(booksDir, "nested/book.epub")
        outputFile.writeText("book")

        assertEquals(
            booksDir.resolve("nested/book.epub").canonicalFile,
            outputFile,
        )
        assertEquals("book", outputFile.readText())
        assertThrows(SecurityException::class.java) {
            prepareLocalBookOutputFile(booksDir, "../outside.epub")
        }
    }

    @Test
    fun `file storage rejects existing symlink to outside`() = withTempDirectory { root ->
        val booksDir = root.resolve("books").apply { mkdirs() }
        val outsideFile = root.resolve("outside.epub").apply { writeText("outside") }
        val link = booksDir.resolve("linked.epub")
        try {
            Files.createSymbolicLink(link.toPath(), outsideFile.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable", error)
        }

        assertThrows(SecurityException::class.java) {
            resolveLocalBookOutputFile(booksDir, "linked.epub")
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("local-book-file-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
