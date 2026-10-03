package io.legado.app.data.repository

import io.legado.app.model.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class RemoteLibraryRepositoryTest {
    private class Store : RemoteLibraryStore {
        val caller = Thread.currentThread(); val threads = mutableListOf<Thread>(); var closed = false; var imported: RemoteLibraryEntry? = null; var requested = ""
        val connection = RemoteLibraryConnection("owned", "https://example.invalid/books/", true, null)
        val original = RemoteBook("book2.txt", "https://example.invalid/books/book2.txt", 123, 9, "txt", false)
        override suspend fun connect(): RemoteLibraryConnection { threads += Thread.currentThread(); return connection }
        override suspend fun list(connection: RemoteLibraryConnection, path: String): List<RemoteBook> { threads += Thread.currentThread(); requested = path; return listOf(original) }
        override suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry) { threads += Thread.currentThread(); imported = entry }
        override fun close() { threads += Thread.currentThread(); closed = true }
    }
    @Test fun actualRepositoryProjectsDetachedRowsAndRunsConnectListImportAndCloseOnIo() = runTest {
        val store = Store(); val repository = DefaultRemoteLibraryRepository(store)
        val connection = repository.connect(); val rows = repository.list(connection)
        assertEquals(connection.root, store.requested); assertEquals("book2.txt", rows.single().name); assertTrue(rows.single().checkable)
        store.original.isOnBookShelf = true; assertFalse(rows.single().onShelf)
        repository.list(connection, "https://example.invalid/sub/"); assertEquals("https://example.invalid/sub/", store.requested)
        repository.import(connection, rows.single()); assertEquals(rows.single(), store.imported)
        repository.close(); assertTrue(store.closed); assertTrue(store.threads.isNotEmpty()); assertTrue(store.threads.all { it !== store.caller })
    }
    @Test fun reimportAllowsExistingBooksButDirectoriesNeverReachTheStore() = runTest {
        val store = Store(); val repository = DefaultRemoteLibraryRepository(store); val connection = repository.connect()
        val existing = RemoteLibraryEntry("existing", "existing.txt", "existing", 1, 1, "txt", true)
        repository.import(connection, existing); assertEquals(existing, store.imported)
        try { repository.import(connection, existing.copy(type = "folder")); fail("directory must be rejected") } catch (_: IllegalArgumentException) {}
        assertEquals(existing, store.imported)
    }
    @Test fun filenameProjectionRetainsLegacyCaseMatchingNaturalNamesAndDirectoryFirstInBothDirections() {
        val rows = listOf(RemoteLibraryEntry("ten", "book10.txt", "ten", 1, 10, "txt", false),
            RemoteLibraryEntry("two", "book2.txt", "two", 2, 2, "txt", false), RemoteLibraryEntry("dir", "books", "dir", 0, 0, "folder", false))
        assertEquals(listOf("dir", "two", "ten"), projectRemoteLibrary(rows, "", RemoteLibrarySort.Name, true).map { it.id })
        assertEquals(listOf("dir", "ten", "two"), projectRemoteLibrary(rows, "", RemoteLibrarySort.Name, false).map { it.id })
        assertEquals(listOf("dir", "ten", "two"), projectRemoteLibrary(rows, "", RemoteLibrarySort.Modified, false).map { it.id })
        assertTrue(projectRemoteLibrary(rows, "Book", RemoteLibrarySort.Name, true).isEmpty())
        assertEquals(listOf("two"), projectRemoteLibrary(rows, "book2", RemoteLibrarySort.Name, true).map { it.id })
        assertFalse(rows.last().checkable)
    }
    @Test fun permissionAndPartialImportFailurePropagateWithoutConvertingThemToSuccess() = runTest {
        var calls = 0; val store = object : RemoteLibraryStore {
            override suspend fun connect() = RemoteLibraryConnection("owned", "root", false, 7)
            override suspend fun list(connection: RemoteLibraryConnection, path: String) = emptyList<RemoteBook>()
            override suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry) { calls++; throw SecurityException("synthetic permission denial") }
            override fun close() {}
        }; val repository = DefaultRemoteLibraryRepository(store)
        try { repository.import(repository.connect(), RemoteLibraryEntry("file", "file.txt", "file", 1, 1, "txt", false)); fail("permission failure must propagate") }
        catch (error: SecurityException) { assertEquals("synthetic permission denial", error.message) }
        assertEquals(1, calls)
    }
}
