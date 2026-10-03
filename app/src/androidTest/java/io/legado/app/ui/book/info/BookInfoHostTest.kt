package io.legado.app.ui.book.info

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.repository.BookDetailChildKind
import io.legado.app.data.repository.BookDetailChildOwner
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.BookDetailMutation
import io.legado.app.data.repository.BookDetailMutationKind
import io.legado.app.data.repository.FileBookDetailChildRepository
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.info.detail.BookDetailViewModel
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.toc.TocActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.login.SourceLoginActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookInfoHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun preparedActivityRestoresCollapsedIntroAndCancelsWithoutChangingBook() = runBlocking {
        val book = fixtureBook().copy(bookUrl = "prepared-" + "large-url".repeat(30_000))
        insert(book)
        val ticket =
            BookInfoNavigation.prepare(
                context,
                BookDetailIdentity(book.name, book.author, book.bookUrl),
            )
        try {
            ActivityScenario.launchActivityForResult<BookInfoActivity>(
                    BookInfoNavigation.intent(context, ticket)
                )
                .use { scenario ->
                    awaitReady(scenario)
                    scenario.onActivity {
                        assertEquals(ticket, it.viewModel.ticket)
                        assertSmallSavedState(it.viewModel)
                        it.viewModel.introExpanded(false)
                    }
                    scenario.recreate()
                    awaitReady(scenario)
                    scenario.onActivity {
                        assertEquals(ticket, it.viewModel.ticket)
                        assertFalse(it.viewModel.state.value.introExpanded)
                        assertSmallSavedState(it.viewModel)
                        it.onBackPressedDispatcher.onBackPressed()
                    }
                    assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
                    assertEquals(
                        book,
                        withContext(Dispatchers.IO) { appDb.bookDao.getBook(book.bookUrl) },
                    )
                }
            awaitReleased(ticket)
        } finally {
            cleanup(book, ticket)
        }
    }

    @Test
    fun legacyIntentDoesNotSaveLargeIdentityAndNativeLoginUsesOriginalSourceContract() =
        runBlocking {
            val book = fixtureBook()
            val source =
                BookSource(
                    bookSourceUrl = book.origin,
                    bookSourceName = "Source",
                    loginUrl = "https://login.example/",
                )
            insert(book, source)
            val opened = CopyOnWriteArrayList<Intent>()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor =
                object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        if (intent.component?.className != SourceLoginActivity::class.java.name)
                            return null
                        opened += Intent(intent)
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
            instrumentation.addMonitor(monitor)
            var ticket: String? = null
            try {
                ActivityScenario.launch<BookInfoActivity>(
                        Intent(context, BookInfoActivity::class.java)
                            .putExtra("name", book.name)
                            .putExtra("author", book.author)
                            .putExtra("bookUrl", book.bookUrl)
                    )
                    .use { scenario ->
                        awaitReady(scenario)
                        scenario.onActivity {
                            ticket = it.viewModel.ticket
                            assertSmallSavedState(it.viewModel)
                        }
                        compose.onNodeWithTag("book-detail-more").performClick()
                        compose.onNodeWithTag("book-detail-menu-${R.string.login}").performClick()
                        compose.waitUntil(timeoutMillis = 5_000) { opened.isNotEmpty() }
                        assertEquals("bookSource", opened.single().getStringExtra("type"))
                        assertEquals(book.origin, opened.single().getStringExtra("key"))
                        assertEquals(book.bookUrl, opened.single().getStringExtra("bookUrl"))
                        scenario.recreate()
                        awaitReady(scenario)
                        assertEquals(1, opened.size)
                        scenario.onActivity { it.finish() }
                    }
                awaitReleased(checkNotNull(ticket))
            } finally {
                instrumentation.removeMonitor(monitor)
                cleanup(book, ticket, source)
            }
        }

    @Test
    fun earlyTocResultOpensReaderOnceWithDeferredHighlightAndPreservesStoredProgress() =
        runBlocking {
            val book = fixtureBook().copy(durChapterIndex = 5, durChapterPos = 71)
            insert(book)
            val ticket =
                BookInfoNavigation.prepare(
                    context,
                    BookDetailIdentity(book.name, book.author, book.bookUrl),
                )
            val openedReaders = CopyOnWriteArrayList<Intent>()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val previousBook = ReadBook.book
            val previousHighlights = ReadBook.highlights.toList()
            val monitor =
                object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                        when (intent.component?.className) {
                            TocActivity::class.java.name ->
                                Instrumentation.ActivityResult(
                                    Activity.RESULT_OK,
                                    Intent()
                                        .putExtra("index", 3)
                                        .putExtra("chapterPos", 21)
                                        .putExtra("chapterChanged", true)
                                        .putExtra("durVolumeIndex", 1)
                                        .putExtra("chapterInVolumeIndex", 2)
                                        .putExtra(
                                            TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH,
                                            8,
                                        )
                                        .putExtra(
                                            TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT,
                                            "Selected text",
                                        ),
                                )
                            ReadBookActivity::class.java.name -> {
                                openedReaders += Intent(intent)
                                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                            }
                            else -> null
                        }
                }
            instrumentation.addMonitor(monitor)
            try {
                ActivityScenario.launch<BookInfoActivity>(
                        BookInfoNavigation.intent(context, ticket)
                    )
                    .use { scenario ->
                        awaitReady(scenario)
                        compose.onNodeWithTag("book-detail-toc").performScrollTo().performClick()
                        compose.waitUntil(timeoutMillis = 5_000) { openedReaders.isNotEmpty() }
                        val reader = openedReaders.single()
                        assertEquals(book.bookUrl, reader.getStringExtra("bookUrl"))
                        assertEquals(3, reader.getIntExtra("index", -1))
                        assertEquals(21, reader.getIntExtra("chapterPos", -1))
                        assertTrue(reader.getBooleanExtra("chapterChanged", false))
                        assertEquals(
                            8,
                            reader.getIntExtra(
                                TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH,
                                -1,
                            ),
                        )
                        assertEquals(
                            "Selected text",
                            reader.getStringExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT),
                        )
                        val actual =
                            withContext(Dispatchers.IO) { appDb.bookDao.getBook(book.bookUrl)!! }
                        assertEquals(5, actual.durChapterIndex)
                        assertEquals(71, actual.durChapterPos)
                        awaitReady(scenario)
                        scenario.recreate()
                        awaitReady(scenario)
                        assertEquals(1, openedReaders.size)
                        scenario.onActivity { it.finish() }
                    }
                awaitReleased(ticket)
            } finally {
                instrumentation.removeMonitor(monitor)
                withContext(Dispatchers.Main) {
                    val owner = previousBook ?: Book(bookUrl = "test-cleanup-owner")
                    ReadBook.book = owner
                    check(ReadBook.applyPreparedHighlights(owner.bookUrl, previousHighlights))
                    ReadBook.book = previousBook
                }
                cleanup(book, ticket)
            }
        }

    @Test
    fun searchPreviewCoverDraftSurvivesRecreationWithoutCreatingBookshelfRow() = runBlocking {
        val book = fixtureBook()
        val source = BookSource(bookSourceUrl = book.origin, enabled = false)
        withContext(Dispatchers.IO) {
            appDb.bookSourceDao.insert(source)
            appDb.searchBookDao.insert(
                SearchBook(
                    bookUrl = book.bookUrl,
                    name = book.name,
                    author = book.author,
                    origin = book.origin,
                    tocUrl = book.tocUrl,
                    coverUrl = book.coverUrl,
                )
            )
            appDb.bookChapterDao.insert(
                BookChapter(bookUrl = book.bookUrl, url = "preview-chapter", title = "Preview")
            )
        }
        val ticket =
            BookInfoNavigation.prepare(
                context,
                BookDetailIdentity(book.name, book.author, book.bookUrl),
            )
        try {
            ActivityScenario.launchActivityForResult<BookInfoActivity>(
                    BookInfoNavigation.intent(context, ticket)
                )
                .use { scenario ->
                    awaitReady(scenario)
                    scenario.onActivity {
                        assertFalse(checkNotNull(it.viewModel.state.value.data).inBookshelf)
                        it.viewModel.mutate(
                            BookDetailMutation(
                                BookDetailMutationKind.Cover,
                                text = "private-preview-cover",
                            )
                        )
                    }
                    compose.waitUntil(timeoutMillis = 5_000) {
                        var changed = false
                        scenario.onActivity {
                            changed =
                                it.viewModel.state.value.canInteract &&
                                    it.viewModel.state.value.data?.book?.cover?.path ==
                                        "private-preview-cover"
                        }
                        changed
                    }
                    scenario.recreate()
                    awaitReady(scenario)
                    scenario.onActivity {
                        assertEquals(
                            "private-preview-cover",
                            it.viewModel.state.value.data?.book?.cover?.path,
                        )
                        assertFalse(checkNotNull(it.viewModel.state.value.data).inBookshelf)
                        it.onBackPressedDispatcher.onBackPressed()
                    }
                    assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
                    assertNull(withContext(Dispatchers.IO) { appDb.bookDao.getBook(book.bookUrl) })
                    assertEquals(
                        book.coverUrl,
                        withContext(Dispatchers.IO) {
                            appDb.searchBookDao.getSearchBook(book.bookUrl)!!.coverUrl
                        },
                    )
                }
            awaitReleased(ticket)
        } finally {
            cleanup(book, ticket, source)
        }
    }

    @Test
    fun sourceCompletionWaitsForResumeAndDuplicateResultAfterRecreationDoesNotRedeliver() =
        runBlocking {
            val original = fixtureBook()
            val originalSource = BookSource(bookSourceUrl = original.origin, enabled = false)
            val replacementSource =
                BookSource(
                    bookSourceUrl = "replacement-${UUID.randomUUID()}",
                    bookSourceName = "Replacement source",
                    enabled = false,
                )
            val replacement =
                original.copy(
                    bookUrl = "replacement-book-${UUID.randomUUID()}",
                    origin = replacementSource.bookSourceUrl,
                    tocUrl = "replacement-toc",
                )
            val chapters =
                listOf(
                    BookChapter(
                        bookUrl = replacement.bookUrl,
                        url = "replacement-chapter",
                        title = "Replacement",
                    )
                )
            insert(original, originalSource)
            withContext(Dispatchers.IO) { appDb.bookSourceDao.insert(replacementSource) }
            val ticket =
                BookInfoNavigation.prepare(
                    context,
                    BookDetailIdentity(original.name, original.author, original.bookUrl),
                )
            val owner =
                BookDetailChildOwner(
                    UUID.randomUUID().toString(),
                    BookDetailChildKind.Source,
                    original.bookUrl,
                    original.origin,
                )
            val callbacks = AtomicInteger()
            val previousBook = ReadBook.book
            val previousHighlights = ReadBook.highlights.toList()
            try {
                ActivityScenario.launch<BookInfoActivity>(
                        BookInfoNavigation.intent(context, ticket)
                    )
                    .use { scenario ->
                        awaitReady(scenario)
                        val registered = CompletableDeferred<Unit>()
                        scenario.onActivity { activity ->
                            activity.lifecycleScope.launch {
                                try {
                                    activity.viewModel.registerChild(owner)
                                    // Establish the same launcher-token association as native
                                    // delivery,
                                    // without opening a live source search against external
                                    // services.
                                    @Suppress("UNCHECKED_CAST")
                                    val tokens =
                                        BookInfoActivity::class
                                            .java
                                            .getDeclaredField("launchedChildTokens")
                                            .apply { isAccessible = true }
                                            .get(activity)
                                            as MutableMap<BookDetailChildKind, String>
                                    tokens[BookDetailChildKind.Source] = owner.token
                                    registered.complete(Unit)
                                } catch (error: Throwable) {
                                    registered.completeExceptionally(error)
                                }
                            }
                        }
                        withTimeout(5_000) { registered.await() }
                        scenario.moveToState(Lifecycle.State.STARTED)
                        scenario.onActivity {
                            it.changeTo(replacementSource, replacement, chapters) {
                                callbacks.incrementAndGet()
                            }
                        }
                        val ledger = FileBookDetailChildRepository(context)
                        withTimeout(5_000) {
                            while (owner.token !in ledger.read(ticket).completed) delay(10)
                        }
                        assertEquals(0, callbacks.get())
                        assertTrue(ledger.read(ticket).deliveredCallbacks.isEmpty())
                        assertEquals(
                            replacement.origin,
                            withContext(Dispatchers.IO) {
                                appDb.bookDao.getBook(replacement.bookUrl)!!.origin
                            },
                        )
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        compose.waitUntil(timeoutMillis = 5_000) { callbacks.get() == 1 }
                        awaitReady(scenario)
                        scenario.recreate()
                        awaitReady(scenario)
                        scenario.onActivity {
                            it.changeTo(replacementSource, replacement, chapters) {
                                callbacks.incrementAndGet()
                            }
                        }
                        // Wait for the same durable receipt through a fresh repository instance.
                        assertEquals(listOf(owner.token), ledger.read(ticket).deliveredCallbacks)
                        compose.waitForIdle()
                        assertEquals(1, callbacks.get())
                        scenario.onActivity { it.finish() }
                    }
                awaitReleased(ticket)
            } finally {
                withContext(Dispatchers.Main) {
                    val ownerBook = previousBook ?: Book(bookUrl = "test-cleanup-owner")
                    ReadBook.book = ownerBook
                    check(ReadBook.applyPreparedHighlights(ownerBook.bookUrl, previousHighlights))
                    ReadBook.book = previousBook
                }
                cleanup(original, ticket, originalSource)
                cleanup(replacement, null, replacementSource)
            }
        }

    private fun awaitReady(scenario: ActivityScenario<BookInfoActivity>) {
        compose.waitUntil(timeoutMillis = 5_000) {
            var ready = false
            scenario.onActivity {
                ready =
                    it.viewModel.state.value.canInteract && !it.viewModel.state.value.networkLoading
            }
            ready
        }
    }

    private fun assertSmallSavedState(model: BookDetailViewModel) {
        val saved =
            BookDetailViewModel::class
                .java
                .getDeclaredField("saved")
                .apply { isAccessible = true }
                .get(model) as SavedStateHandle
        for (key in listOf("name", "author", "bookUrl")) assertFalse(saved.contains(key))
    }

    private fun fixtureBook(): Book {
        val token = UUID.randomUUID().toString()
        return Book(
            bookUrl = "book-info-$token",
            name = "Name $token",
            author = "Author",
            origin = "source-$token",
            tocUrl = "toc-$token",
            coverUrl = "use_default_cover",
            intro = (1..10).joinToString("\n") { "Introduction paragraph $it" },
            totalChapterNum = 10,
        )
    }

    private suspend fun insert(book: Book, source: BookSource? = null) =
        withContext(Dispatchers.IO) {
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(
                BookChapter(
                    bookUrl = book.bookUrl,
                    url = "chapter-${book.bookUrl}",
                    title = "Chapter",
                )
            )
            source?.let { appDb.bookSourceDao.insert(it) }
        }

    private suspend fun awaitReleased(ticket: String) =
        withTimeout(5_000) {
            while (
                withContext(Dispatchers.IO) {
                    File(context.filesDir, "book-detail-sessions/$ticket.json").exists()
                }
            ) delay(10)
        }

    private suspend fun cleanup(book: Book, ticket: String?, source: BookSource? = null) =
        withContext(Dispatchers.IO) {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            source?.let { appDb.bookSourceDao.delete(it) }
            ticket?.let { id ->
                for (directory in listOf("book-detail-sessions", "book-detail-children")) {
                    for (suffix in
                        listOf(
                            "json",
                            "json.bak",
                            "json.new",
                            "closed",
                            "closed.bak",
                            "closed.new",
                        )) {
                        File(context.filesDir, "$directory/$id.$suffix").delete()
                    }
                }
            }
        }
}
