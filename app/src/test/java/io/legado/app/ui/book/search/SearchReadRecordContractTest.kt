package io.legado.app.ui.book.search

import io.legado.app.data.entities.ReadRecordBook
import io.legado.app.help.book.ReadRecordIndex
import io.legado.app.model.webBook.BookSearchMembership
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchReadRecordContractTest {

    // Live Room membership and the menu toggle are covered by BookSearchMetadataRepositoryTest
    // and BookSearchCommandsTest. The row marker is exercised by BookSearchScreenComposeTest.
    @Test
    fun membershipMatchesAuthorsAndKeepsLegacyUnknownAuthorFallback() {
        val membership =
            BookSearchMembership(
                shelfKeys = setOf("Title-Writer"),
                readRecords = ReadRecordIndex.of(listOf(ReadRecordBook("Title", "Writer"))),
            )
        assertTrue(membership.onShelf("different-origin", "Title", "Writer"))
        assertFalse(membership.onShelf("different-origin", "Title", "Other"))
        assertTrue(membership.hasRead("Title", "Writer"))
        assertFalse(membership.hasRead("Title", "Other"))
        assertTrue(membership.hasRead("Title", ""))
        assertFalse(membership.hasRead("Unknown", "Writer"))
    }

    @Test
    fun `reading a book stores the author with the record`() {
        listOf(
                Triple(
                    "src/main/java/io/legado/app/model/ReadBook.kt",
                    "val currentBook = book?.copy() ?: return",
                    "getRecord(AppConst.androidId, book.name, book.author)",
                ),
                Triple(
                    "src/main/java/io/legado/app/model/ReadManga.kt",
                    "val currentBook = book?.copy() ?: return",
                    "getRecord(AppConst.androidId, book.name, book.author)",
                ),
            )
            .forEach { (path, authorCapture, authorAssignment) ->
                val source = projectFile(path).readText().replace(Regex("\\s+"), " ")
                assertTrue(path, source.contains(authorCapture))
                assertTrue(path, source.contains(authorAssignment))
            }

        val audioPlay =
            projectFile("src/main/java/io/legado/app/model/AudioPlay.kt")
                .readText()
                .replace(Regex("\\s+"), " ")
        assertTrue(
            audioPlay.contains(
                "book?.takeUnless(readTimeTracker::isForBook)?.let { resetReadRecord(it) }"
            )
        )
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
