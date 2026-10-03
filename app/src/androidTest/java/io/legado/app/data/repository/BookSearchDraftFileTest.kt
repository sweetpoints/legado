package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class BookSearchDraftFileTest {
    @Test fun largeResultsAndEditableQueryRestoreWithoutBundleAndCloseRejectsLateWriters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val repository = FileBookSearchDraftRepository(context)
        val owned = UUID.randomUUID().toString(); val neighbor = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "book-search-drafts")
        try {
            repository.open(owned); repository.open(neighbor)
            val huge = "synthetic-search-content".repeat(60000)
            val result = BookSearchResult.from(SearchBook(bookUrl = huge, name = "Title", variable = huge).apply { infoHtml = huge; addOrigin("another") })
            val draft = BookSearchDraft(revision = 10, query = huge, scope = huge, results = listOf(result), filterDraft = huge,
                effects = listOf(BookSearchReceipt("receipt", BookSearchEffect.BookInfo, resultId = result.id)), interrupted = true)
            repository.write(owned, draft); repository.write(owned, draft.copy(revision = 9, query = "stale"))
            assertEquals(draft, FileBookSearchDraftRepository(context).open(owned))
            val file = File(directory, "$owned.json"); file.renameTo(File(file.path + ".bak")); assertEquals(draft, repository.open(owned))
            repository.release(owned); assertTrue(File(file.path + ".closed").exists()); assertFalse(file.exists())
            assertTrue(runCatching { repository.write(owned, draft.copy(revision = 20)) }.isFailure); assertTrue(runCatching { repository.open(owned) }.isFailure)
            assertEquals(BookSearchDraft(), repository.open(neighbor))
            val marker = File(file.path + ".closed"); marker.renameTo(File(marker.path + ".bak")); assertTrue(runCatching { repository.open(owned) }.isFailure)
        } finally {
            repository.release(neighbor)
            listOf(owned, neighbor).forEach { id -> listOf(".json", ".json.bak", ".json.new", ".json.closed", ".json.closed.bak", ".json.closed.new").forEach { File(directory, id + it).delete() } }
        }
    }
}
