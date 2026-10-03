package io.legado.app.ui.book.download

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.ChapterDownloadSessionRepository
import io.legado.app.model.download.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ChapterDownloadRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<ChapterDownloadViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @After
    fun after() {
        compose.runOnIdle {
            gates.forEach { it.complete(Unit) }
            models.forEach { it.stop() }
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle
            get() = registry
    }

    private class Store : ChapterDownloadSessionRepository {
        var value = ChapterDownloadSession("{}", ChapterDownloadMode.Audio, 4, 8)
        var entered = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun create(
            book: Book,
            mode: ChapterDownloadMode,
            initialChapter: Int,
            chapterCount: Int,
        ) = "ticket"

        override suspend fun read(ticket: String) = value

        override suspend fun write(ticket: String, value: ChapterDownloadSession) {
            if (value.completed && gate != null) {
                val block = gate!!
                gate = null
                entered = true
                withContext(NonCancellable) { block.await() }
            }
            this.value = value
        }

        override suspend fun book(value: ChapterDownloadSession) = Book(bookUrl = "owned")

        override suspend fun release(ticket: String) {}
    }

    private fun model(store: Store): ChapterDownloadViewModel {
        lateinit var model: ChapterDownloadViewModel
        compose.runOnIdle {
            model = ChapterDownloadViewModel(SavedStateHandle(), store, "ticket")
            models += model
        }
        return model
    }

    @Test
    fun confirmedRangeWaitsForResumeThenConsumesDiskReceiptBeforeSingleDelivery() {
        val store = Store()
        val model = model(store)
        val owner = Owner()
        var calls = 0
        var closes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ChapterDownloadRoute(
                        model,
                        {
                            calls++
                            assertTrue(store.value.completed)
                            assertNull(store.value.pending)
                            assertEquals(ChapterDownloadRange(3, 7), it.range)
                        },
                        { closes++ },
                        { error(it) },
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10000) { model.state.value.loaded }
        compose.runOnIdle { model.confirm() }
        compose.waitUntil(timeoutMillis = 10000) { model.state.value.pending != null }
        assertEquals(0, calls)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(timeoutMillis = 10000) { closes == 1 }
        assertEquals(1, calls)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, calls)
        assertEquals(1, closes)
    }

    @Test
    fun pauseDuringAcceptedWritePreservesUndeliveredReceiptForNextResume() {
        val store = Store()
        val model = model(store)
        val owner = Owner()
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        gates += gate
        store.gate = gate
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme { ChapterDownloadRoute(model, { calls++ }, {}, { error(it) }) }
            }
        }
        compose.waitUntil(timeoutMillis = 10000) { model.state.value.loaded }
        compose.runOnIdle { model.confirm() }
        compose.waitUntil(timeoutMillis = 10000) { store.entered }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            gate.complete(Unit)
        }
        compose.waitUntil(timeoutMillis = 10000) { !model.state.value.busy }
        assertEquals(0, calls)
        assertFalse(store.value.completed)
        assertNotNull(store.value.pending)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(timeoutMillis = 10000) { calls == 1 }
        assertTrue(store.value.completed)
    }

    @Test
    fun completedPrivateReceiptRestoresCloseWithoutDispatchingNativeWorkAgain() {
        val store = Store().apply { value = value.copy(revision = 8, completed = true) }
        val model = model(store)
        var closes = 0
        compose.setContent {
            LegadoComposeTheme {
                ChapterDownloadRoute(
                    model,
                    { error("Repeated native work") },
                    { closes++ },
                    { error(it) },
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10000) { closes == 1 }
        assertNull(store.value.pending)
    }
}
