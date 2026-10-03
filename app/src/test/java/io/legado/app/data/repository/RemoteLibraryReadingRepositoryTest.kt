package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import io.legado.app.model.remote.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RemoteLibraryReadingRepositoryTest {
    private class Store : RemoteLibraryReadingStore {
        val thread = Thread.currentThread()
        val calls = mutableListOf<Thread>()
        var uri: String? = "content://synthetic/tree"
        var found: String? = "content://synthetic/archive"
        var names = listOf("a.txt")
        var book: Book? = null
        var imported: Pair<String, String>? = null

        private fun called() {
            calls += Thread.currentThread()
        }

        override fun storageUri(): String? {
            called()
            return uri
        }

        override fun storageHelp(): String {
            called()
            return "synthetic help"
        }

        override fun storageUri(value: String) {
            called()
            uri = value
        }

        override fun archive(name: String): Boolean {
            called()
            return name.endsWith(".zip")
        }

        override fun bookByFile(name: String): Book? {
            called()
            return book
        }

        override fun readBook(id: String): Book? {
            called()
            return book?.takeIf { it.bookUrl == id }
        }

        override fun archiveUri(name: String): String? {
            called()
            return found
        }

        override fun archiveNames(uri: String): List<String> {
            called()
            return names
        }

        override fun importArchive(uri: String, name: String): Book {
            called()
            imported = uri to name
            return Book(bookUrl = "imported")
        }
    }

    private fun entry(name: String) =
        RemoteLibraryEntry(name, name, "https://example.invalid/$name", 1, 2, "zip", true)

    @Test
    fun storageAndExistingBookPreparationRunsOnIoAndReturnsDetachedFreshBookOnlyAtDelivery() =
        runTest {
            val store = Store()
            val repository = DefaultRemoteLibraryReadingRepository(store)
            assertTrue(repository.storageConfigured())
            assertEquals("synthetic help", repository.storageHelp())
            repository.storageUri("content://new-tree")
            assertEquals("content://new-tree", store.uri)
            store.book = Book(bookUrl = "existing", name = "Original")
            assertEquals(
                RemoteLibraryReadTarget.Open("existing"),
                repository.prepare(entry("a.txt")),
            )
            val fresh = repository.readBook("existing")!!
            store.book!!.name = "Changed"
            assertEquals("Original", fresh.name)
            assertTrue(store.calls.all { it !== store.thread })
        }

    @Test
    fun archiveMissingDownloadUnsupportedSingleAndMultipleEntriesKeepAllOriginalReadBranches() =
        runTest {
            val store = Store()
            val repository = DefaultRemoteLibraryReadingRepository(store)
            val row = entry("archive.zip")
            store.uri = null
            assertEquals(RemoteLibraryReadTarget.None, repository.prepare(row))
            store.uri = "content://tree"
            store.found = null
            assertEquals(RemoteLibraryReadTarget.DownloadArchive(row), repository.prepare(row))
            store.found = "content://archive"
            store.names = emptyList()
            assertEquals(RemoteLibraryReadTarget.UnsupportedArchive, repository.prepare(row))
            store.names = listOf("a.txt")
            assertEquals(
                RemoteLibraryReadTarget.ImportArchive("content://archive", "a.txt"),
                repository.prepare(row),
            )
            store.names = listOf("a.txt", "b.txt")
            assertEquals(
                RemoteLibraryReadTarget.ChooseArchive("content://archive", store.names),
                repository.prepare(row),
            )
            store.book = Book(bookUrl = "existing")
            assertEquals(
                RemoteLibraryReadTarget.Open("existing"),
                repository.chooseArchive("content://archive", "b.txt"),
            )
        }

    @Test
    fun missingArchiveBookRequiresExplicitImportAndExactSelectedMetadataIsPassedOnIo() = runTest {
        val store = Store()
        val repository = DefaultRemoteLibraryReadingRepository(store)
        assertEquals(
            RemoteLibraryReadTarget.ImportArchive("content://archive", "folder/chapter.txt"),
            repository.chooseArchive("content://archive", "folder/chapter.txt"),
        )
        assertNull(store.imported)
        assertEquals(
            "imported",
            repository.importArchive("content://archive", "folder/chapter.txt"),
        )
        assertEquals("content://archive" to "folder/chapter.txt", store.imported)
        assertTrue(store.calls.all { it !== store.thread })
    }
}
