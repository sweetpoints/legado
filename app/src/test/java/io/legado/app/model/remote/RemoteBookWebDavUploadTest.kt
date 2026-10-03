package io.legado.app.model.remote

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.archiveName
import io.legado.app.lib.webdav.isWebDavOverwriteConflict
import io.legado.app.model.analyzeRule.CustomUrl
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBookWebDavUploadTest {

    @Test
    fun `archive upload keeps the archive file name`() {
        val archiveBook =
            Book(
                origin = "${BookType.localTag}::source.zip",
                originName = "chapter.txt",
                type = BookType.text or BookType.local or BookType.archive,
            )

        assertEquals("source.zip", remoteBookUploadFileName(archiveBook))
        assertEquals("chapter.txt", remoteBookUploadFileName(Book(originName = "chapter.txt")))
    }

    @Test
    fun `archive name ignores custom WebDAV attributes`() {
        val archiveBook =
            Book(
                origin =
                    BookType.webDavTag +
                        CustomUrl("https://example.com/books/source.zip")
                            .putAttribute("serverID", 7L)
                            .toString(),
                type = BookType.text or BookType.local or BookType.archive,
            )

        assertEquals("source.zip", archiveBook.archiveName)
        assertEquals("source.zip", remoteBookUploadFileName(archiveBook))
    }

    @Test
    fun `conditional upload conflicts require overwrite confirmation`() {
        assertTrue(isWebDavOverwriteConflict(409))
        assertTrue(isWebDavOverwriteConflict(412))
        assertFalse(isWebDavOverwriteConflict(null))
        assertFalse(isWebDavOverwriteConflict(401))
        assertFalse(isWebDavOverwriteConflict(500))
    }

    @Test
    fun `download upload choice is remembered and checks before overwrite`() {
        val localConfig = readProjectFile("src/main/java/io/legado/app/help/config/LocalConfig.kt")
        val remoteBook =
            readProjectFile("src/main/java/io/legado/app/model/remote/RemoteBookWebDav.kt")
        val webDav = readProjectFile("src/main/java/io/legado/app/lib/webdav/WebDav.kt")

        assertTrue(localConfig.contains("var uploadImportedBookToWebDav: Boolean"))
        assertTrue(
            remoteBook.contains("findExactRemoteBook(getRemoteBookList(rootBookUrl), fileName)")
        )
        assertTrue(remoteBook.contains("webDav.upload(localBookUri, overwrite = overwrite)"))
        assertTrue(remoteBook.contains("webDav.upload(localBookUri.path!!, overwrite = overwrite)"))
        val conditionalHeader = "if (!overwrite) addHeader(\"If-None-Match\", \"*\")"
        assertEquals(2, webDav.split(conditionalHeader).size - 1)
    }

    @Test
    fun `local book deletion resolves the exact WebDAV file before local removal`() {
        val remoteBook =
            readProjectFile("src/main/java/io/legado/app/model/remote/RemoteBookWebDav.kt")

        assertTrue(remoteBook.contains("val fileName = remoteBookUploadFileName(book)"))
        assertTrue(
            remoteBook.contains("findExactRemoteBook(getRemoteBookList(rootBookUrl), fileName)")
        )
        assertTrue(remoteBook.contains("WebDav(remoteBook.path, authorization).delete()"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
