package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class AddBookLinkRepositoryTest {
    private val url = "https://books.invalid/detail"
    private fun source(name: String, pattern: String? = null) = BookSource("https://$name.invalid", name, bookUrlPattern = pattern)
    private fun target(book: Book) = BookLinkTarget(book.name, book.author, book.bookUrl)
    private fun exercise(action: suspend (Fake, AddBookLinkRepository) -> Unit) {
        val caller = Thread.currentThread()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val fake = Fake(); val repository = DefaultAddBookLinkRepository(fake, dispatcher)
            runBlocking { action(fake, repository) }
            assertTrue(fake.threads.isNotEmpty()); assertTrue(fake.threads.all { it !== caller })
        }
    }
    @Test fun existingShelfBookBypassesUrlValidationNetworkAndSearchInsert() = exercise { fake, repo ->
        val existing = Book(bookUrl = "local-uri", name = "Existing", author = "Author")
        fake.existing = existing
        assertEquals(target(existing), repo.resolve("session", "local-uri"))
        assertEquals(listOf("restore", "existing", "complete"), fake.calls)
        assertTrue(fake.saved.isEmpty())
    }
    @Test fun explicitOriginTakesPriorityEvenIfItIsNotTheBaseSource() = exercise { fake, repo ->
        val explicit = source("explicit"); fake.explicit = explicit; fake.base = source("base")
        val requested = "$url,{\"origin\":\"${explicit.bookSourceUrl}\"}"
        val result = repo.resolve("session", requested)
        assertEquals("explicit book", result.name)
        assertEquals(listOf("explicit"), fake.detailNames)
        assertEquals(explicit.bookSourceUrl, fake.saved.single().origin)
        assertEquals(requested, fake.saved.single().bookUrl)
        assertFalse(fake.calls.contains("base"))
    }
    @Test fun failedExplicitDetailsFallsBackToEnabledBaseThenOrderedPatternSources() = exercise { fake, repo ->
        fake.explicit = source("explicit"); fake.base = source("base"); fake.failNames += listOf("explicit", "base", "first")
        fake.patterns = listOf(source("invalid", "["), source("unmatched", "other"), source("first", "https://.*"), source("second", "https://.*"), source("unused", "https://.*"))
        val result = repo.resolve("session", "$url,{\"origin\":\"https://explicit.invalid\"}")
        assertEquals("second book", result.name)
        assertEquals(listOf("explicit", "base", "first", "second"), fake.detailNames)
        assertEquals("https://second.invalid", fake.saved.single().origin)
    }
    @Test fun malformedOptionsIgnoreExplicitOriginButStillTryBase() = exercise { fake, repo ->
        fake.base = source("base")
        assertEquals("base book", repo.resolve("session", "$url,{invalid}").name)
        assertFalse(fake.calls.any { it.startsWith("origin:") })
    }
    @Test fun matchingPatternUsesFullUrlAndStopsAfterFirstSuccessfulSource() = exercise { fake, repo ->
        fake.patterns = listOf(source("not-full-match", "detail"), source("pattern", "https://books\\.invalid/detail"), source("later", ".*"))
        assertEquals("pattern book", repo.resolve("session", url).name)
        assertEquals(listOf("pattern"), fake.detailNames)
    }
    @Test fun invalidUrlAndMissingSourceNeverSaveSearchBook() = exercise { fake, repo ->
        val invalid = runCatching { repo.resolve("session", "not a URL") }
        assertEquals("书籍地址格式不对", invalid.exceptionOrNull()?.message)
        val missing = runCatching { repo.resolve("session", url) }
        assertEquals("未找到匹配书源", missing.exceptionOrNull()?.message)
        assertTrue(fake.saved.isEmpty()); assertNull(fake.cached)
    }
    @Test fun cancellationFromDetailsIsNotTreatedAsARecoverableSourceMismatch() = exercise { fake, repo ->
        fake.base = source("base"); fake.cancelName = "base"; fake.patterns = listOf(source("fallback", ".*"))
        val result = runCatching { repo.resolve("session", url) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(listOf("base"), fake.detailNames); assertTrue(fake.saved.isEmpty()); assertNull(fake.cached)
    }
    @Test fun completionCacheRestoresMetadataWithoutRepeatingNetworkOrDatabaseWrite() = exercise { fake, repo ->
        fake.base = source("base"); val first = repo.resolve("session", url)
        val calls = fake.calls.size; val second = repo.resolve("session", url)
        assertEquals(first, second); assertEquals(listOf("restore"), fake.calls.drop(calls))
        assertEquals(1, fake.saved.size); assertEquals(listOf("base"), fake.detailNames)
    }
    @Test fun searchInsertFailureDoesNotPublishSuccessfulResult() = exercise { fake, repo ->
        fake.base = source("base"); fake.saveFails = true
        assertEquals("disk", runCatching { repo.resolve("session", url) }.exceptionOrNull()?.message)
        assertNull(fake.cached); assertFalse(fake.calls.contains("complete"))
    }
    @Test fun blankUrlFailsBeforeAnyStoreOrNetworkCall() {
        val fake = Fake()
        runBlocking { assertEquals("url不能为空", runCatching { DefaultAddBookLinkRepository(fake).resolve("session", "  ") }.exceptionOrNull()?.message) }
        assertTrue(fake.calls.isEmpty())
    }
    private class Fake : AddBookLinkStore {
        val threads = mutableListOf<Thread>(); val calls = mutableListOf<String>(); val detailNames = mutableListOf<String>()
        var cached: BookLinkTarget? = null; var existing: Book? = null; var explicit: BookSource? = null; var base: BookSource? = null
        var patterns = emptyList<BookSource>(); val failNames = mutableListOf<String>(); var cancelName: String? = null
        val saved = mutableListOf<Book>(); var saveFails = false
        private fun called(call: String) { threads += Thread.currentThread(); calls += call }
        override suspend fun restore(session: String): BookLinkTarget? { called("restore"); return cached }
        override suspend fun existing(url: String): Book? { called("existing"); return existing }
        override suspend fun source(origin: String): BookSource? { called("origin:$origin"); return explicit }
        override suspend fun baseSource(baseUrl: String): BookSource? { called("base"); assertEquals("https://books.invalid", baseUrl); return base }
        override suspend fun patternSources(): List<BookSource> { called("patterns"); return patterns }
        override suspend fun details(url: String, source: BookSource): Book {
            called("details"); detailNames += source.bookSourceName
            if (source.bookSourceName == cancelName) throw CancellationException("cancel")
            if (source.bookSourceName in failNames) error("bad source")
            return Book(bookUrl = url, name = "${source.bookSourceName} book", author = "Author", origin = source.bookSourceUrl)
        }
        override suspend fun saveSearchBook(book: Book) { called("save"); if (saveFails) error("disk"); saved += book.copy() }
        override suspend fun complete(session: String, target: BookLinkTarget) { called("complete"); cached = target }
    }
}
