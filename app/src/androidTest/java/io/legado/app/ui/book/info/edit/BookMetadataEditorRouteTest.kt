package io.legado.app.ui.book.info.edit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookMetadataEditorRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<BookMetadataEditorViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @After
    fun after() {
        compose.runOnIdle {
            gates.forEach { it.complete(Unit) }
            models.forEach { it.stop() }
        }
    }

    private fun model(
        books: Books,
        store: Store,
        saved: SavedStateHandle = SavedStateHandle(),
    ): BookMetadataEditorViewModel {
        lateinit var vm: BookMetadataEditorViewModel
        compose.runOnIdle {
            vm =
                BookMetadataEditorViewModel(
                    saved,
                    books,
                    store,
                    object : BookMetadataCoverImportRepository {
                        override suspend fun install(uri: String) = uri
                    },
                    "book",
                )
            models += vm
        }
        return vm
    }

    @Test
    fun committedReceiptWaitsForResumeAndIsConsumedBeforeReaderCallbackThenClosesOkOnce() {
        val books = Books()
        val store = Store(books)
        val model = model(books, store)
        val owner = Owner()
        val results = mutableListOf<Boolean>()
        var callbacks = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    BookMetadataEditorRoute(
                        model,
                        { results += it },
                        { _, _ ->
                            callbacks++
                            assertTrue(store.records[model.ticket]!!.finished)
                            assertNull(store.records[model.ticket]!!.completion)
                        },
                        { _, _ -> error("Navigation") },
                        { error(it) },
                        reader = EmptyReader(),
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5000) { model.state.value.loaded }
        compose.runOnIdle { model.save() }
        compose.waitUntil(timeoutMillis = 5000) { model.state.value.draft?.completion != null }
        assertEquals(0, callbacks)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(timeoutMillis = 5000) { results.size == 1 }
        assertEquals(listOf(true), results)
        assertEquals(1, callbacks)
        assertEquals(1, books.saves)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, callbacks)
        assertEquals(1, results.size)
    }

    @Test
    fun pausedNonCooperativeNativeClaimRollsBackAndNextResumeDeliversOnce() {
        val books = Books()
        val store = Store(books)
        val vm = model(books, store)
        val owner = Owner()
        var deliveries = 0
        val gate = CompletableDeferred<Unit>()
        gates += gate
        store.claimGate = gate
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    BookMetadataEditorRoute(
                        vm,
                        {},
                        { _, _ -> },
                        { nav, _ ->
                            deliveries++
                            assertEquals(nav.token, store.records[vm.ticket]!!.pickerOwner)
                            assertNull(store.records[vm.ticket]!!.navigation)
                        },
                        { error(it) },
                        reader = EmptyReader(),
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5000) { vm.state.value.loaded }
        compose.runOnIdle { vm.navigate(BookMetadataAction.PickCover) }
        compose.waitUntil(timeoutMillis = 5000) { store.claimStarted }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        assertEquals(0, deliveries)
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(timeoutMillis = 5000) { !vm.state.value.busy }
        assertNotNull(store.records[vm.ticket]!!.navigation)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(timeoutMillis = 5000) { deliveries == 1 }
        assertEquals(1, deliveries)
    }

    @Test
    fun finishedClosedRestorationReturnsOkWithoutReloadSaveOrReaderCallback() {
        val books = Books()
        val store = Store(books)
        val vm =
            model(
                books,
                store,
                SavedStateHandle(
                    mapOf(
                        BookMetadataEditorViewModel.KEY to "old",
                        "book.metadata.closed" to true,
                        "book.metadata.completed" to true,
                    )
                ),
            )
        val results = mutableListOf<Boolean>()
        compose.setContent {
            LegadoComposeTheme {
                BookMetadataEditorRoute(
                    vm,
                    { results += it },
                    { _, _ -> error("Duplicate reader callback") },
                    { _, _ -> error("Duplicate native action") },
                    { error(it) },
                    reader = EmptyReader(),
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5000) { results.size == 1 }
        assertEquals(listOf(true), results)
        assertEquals(0, books.loads)
        assertEquals(0, books.saves)
    }

    @Test
    fun finishedPrivateReceiptRestorationClosesWithoutDeliveringConsumedCallbackAgain() {
        val books = Books()
        val store = Store(books)
        store.records["finished"] = store.initial().copy(finished = true)
        val vm =
            model(
                books,
                store,
                SavedStateHandle(mapOf(BookMetadataEditorViewModel.KEY to "finished")),
            )
        val results = mutableListOf<Boolean>()
        compose.setContent {
            LegadoComposeTheme {
                BookMetadataEditorRoute(
                    vm,
                    { results += it },
                    { _, _ -> error("Consumed callback") },
                    { _, _ -> error("Navigation") },
                    { error(it) },
                    reader = EmptyReader(),
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5000) { results.size == 1 }
        assertEquals(listOf(true), results)
        assertEquals(0, books.saves)
    }

    @Test
    fun fragmentSavedStateFenceKeepsReceiptUntilHostCanSafelyHandleIt() {
        val books = Books()
        val store = Store(books)
        val vm = model(books, store)
        var safe = false
        var callbacks = 0
        compose.setContent {
            LegadoComposeTheme {
                BookMetadataEditorRoute(
                    vm,
                    {},
                    { _, _ -> callbacks++ },
                    { _, _ -> error("Navigation") },
                    { error(it) },
                    { safe },
                    reader = EmptyReader(),
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5000) { vm.state.value.loaded }
        compose.runOnIdle { vm.save() }
        compose.waitUntil(timeoutMillis = 5000) { vm.state.value.draft?.completion != null }
        assertEquals(0, callbacks)
        compose.runOnIdle {
            safe = true
            vm.failure(IllegalStateException("trigger state delivery"))
        }
        compose.waitUntil(timeoutMillis = 5000) { callbacks == 1 }
        assertEquals(1, books.saves)
    }

    @Test
    fun pauseDuringNonCooperativeHighlightPreparationLeavesCompletionUnconsumedUntilNextResume() {
        val books = Books()
        val store = Store(books)
        val vm = model(books, store)
        val owner = Owner()
        var callbacks = 0
        var entered = false
        val gate = CompletableDeferred<Unit>()
        gates += gate
        val reader =
            object : BookMetadataReaderRepository {
                override suspend fun loadHighlights(
                    bookUrl: String
                ): List<io.legado.app.data.entities.BookHighlight> {
                    entered = true
                    withContext(NonCancellable) { gate.await() }
                    return emptyList()
                }
            }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    BookMetadataEditorRoute(
                        vm,
                        {},
                        { _, _ -> callbacks++ },
                        { _, _ -> error("Navigation") },
                        { error(it) },
                        reader = reader,
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5000) { vm.state.value.loaded }
        compose.runOnIdle { vm.save() }
        compose.waitUntil(timeoutMillis = 5000) { entered }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            gate.complete(Unit)
        }
        compose.waitForIdle()
        assertEquals(0, callbacks)
        assertNotNull(store.records[vm.ticket]!!.completion)
        assertFalse(vm.state.value.finished)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(timeoutMillis = 5000) { callbacks == 1 }
        assertEquals(1, books.saves)
    }

    private class EmptyReader : BookMetadataReaderRepository {
        override suspend fun loadHighlights(bookUrl: String) =
            emptyList<io.legado.app.data.entities.BookHighlight>()
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle
            get() = registry
    }

    private class Books : BookMetadataEditorRepository {
        var loads = 0
        var saves = 0
        val book =
            BookMetadataSnapshot(
                "book",
                "Original",
                "Author",
                8,
                "source",
                "/missing",
                null,
                null,
                "intro",
                null,
            )

        override suspend fun load(bookUrl: String): BookMetadataSnapshot {
            loads++
            return book
        }

        override suspend fun save(
            input: BookMetadataInput,
            journal: (BookMetadataSave) -> Unit,
        ): BookMetadataSnapshot {
            saves++
            journal(BookMetadataSave("before", "target"))
            return book.copy(name = input.name)
        }

        override suspend fun recover(plan: BookMetadataSave) = book
    }

    private class Store(val books: Books) : BookMetadataEditorSessionRepository {
        val records = mutableMapOf<String, BookMetadataDraft>()
        var claimGate: CompletableDeferred<Unit>? = null
        var claimStarted = false

        fun initial() =
            BookMetadataDraft(
                "book",
                books.book,
                BookMetadataInput("book", "Original", "Author", 0, "/missing", "intro"),
                books.book.preview(),
            )

        override suspend fun read(ticket: String) = records[ticket]

        override suspend fun write(ticket: String, draft: BookMetadataDraft) {
            if (records[ticket]?.navigation != null && draft.navigation == null) {
                val gate = claimGate
                claimGate = null
                if (gate != null) {
                    claimStarted = true
                    withContext(NonCancellable) { gate.await() }
                }
            }
            records[ticket] = draft
        }

        override suspend fun save(ticket: String, draft: BookMetadataDraft) =
            draft
                .copy(
                    completion = BookMetadataCompletion("result", books.save(draft.input!!) {}),
                    revision = draft.revision + 1,
                )
                .also { records[ticket] = it }

        override suspend fun release(ticket: String) {
            records.remove(ticket)
        }
    }
}
