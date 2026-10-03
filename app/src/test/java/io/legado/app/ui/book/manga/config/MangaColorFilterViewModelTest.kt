package io.legado.app.ui.book.manga.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.MangaColorFilterRepository
import io.legado.app.data.preferences.MangaColorFilterValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MangaColorFilterViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadingNormalizesAllChannelsWithoutPersistingOrPreviewing() =
        runTest(dispatcher) {
            val repository = FakeRepository(MangaColorFilterValues(-1, 300, 20, 30, 40))
            val model = MangaColorFilterViewModel(repository, SavedStateHandle())
            runCurrent()
            assertEquals(MangaColorFilterValues(0, 255, 20, 30, 40), model.state.value.values)
            assertFalse(model.state.value.loading)
            assertEquals(0, model.state.value.previewRevision)
            assertTrue(repository.saved.isEmpty())
        }

    @Test
    fun channelChangesClampAndDismissalSavesExactlyOnce() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = MangaColorFilterViewModel(repository, SavedStateHandle())
            runCurrent()
            model.change(MangaColorChannel.BRIGHTNESS, -1)
            model.change(MangaColorChannel.RED, 256)
            model.change(MangaColorChannel.GREEN, 12)
            model.change(MangaColorChannel.BLUE, 34)
            model.change(MangaColorChannel.ALPHA, 56)
            val expected = MangaColorFilterValues(0, 255, 12, 34, 56)
            assertEquals(expected, model.state.value.values)
            assertEquals(5, model.state.value.previewRevision)
            assertTrue(repository.saved.isEmpty())
            model.finish()
            model.finish()
            model.change(MangaColorChannel.RED, 1)
            assertEquals(listOf(expected), repository.saved)
            assertEquals(expected, model.state.value.values)
            assertTrue(model.state.value.finished)
        }

    @Test
    fun restoredDraftSkipsReloadAndDoesNotPersistUntilTrueDismissal() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val handle = SavedStateHandle()
            val original = MangaColorFilterViewModel(repository, handle)
            runCurrent()
            original.change(MangaColorChannel.RED, 123)
            original.change(MangaColorChannel.ALPHA, 200)
            val restored =
                MangaColorFilterViewModel(
                    repository,
                    SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
                )
            runCurrent()
            assertEquals(1, repository.loadCount)
            assertEquals(original.state.value.values, restored.state.value.values)
            assertEquals(1, restored.state.value.previewRevision)
            assertTrue(repository.saved.isEmpty())
            restored.finish()
            assertEquals(listOf(original.state.value.values), repository.saved)
        }

    @Test
    fun readerCallbacksAlwaysReceiveIndependentMutableCopies() {
        val values = MangaColorFilterValues(1, 2, 3, 4, 5)
        val first = values.toReaderConfig()
        first.r = 222
        first.l = 111
        val second = values.toReaderConfig()
        assertEquals(MangaColorFilterConfig(r = 2, g = 3, b = 4, a = 5, l = 1), second)
        assertNotSame(first, second)
        assertEquals(2, values.red)
    }

    @Test
    fun lateLoadNeverOverwritesEditedOrSavedDraft() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MangaColorFilterValues>()
            val repository =
                FakeRepository().apply {
                    loadAction = { withContext(NonCancellable) { pending.await() } }
                }
            val handle = SavedStateHandle()
            val model = MangaColorFilterViewModel(repository, handle)
            runCurrent()
            model.change(MangaColorChannel.BLUE, 77)
            model.finish()
            pending.complete(MangaColorFilterValues(99, 99, 99, 99, 99))
            runCurrent()
            assertEquals(77, model.state.value.values.blue)
            assertEquals(77, handle.get<Int>("manga.filter.b"))
            assertEquals(listOf(MangaColorFilterValues(blue = 77)), repository.saved)
        }

    @Test
    fun earlyDismissCancelsReadWithoutWritingDefaults() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MangaColorFilterValues>()
            val repository =
                FakeRepository().apply {
                    loadAction = { withContext(NonCancellable) { pending.await() } }
                }
            val model = MangaColorFilterViewModel(repository, SavedStateHandle())
            runCurrent()
            model.finish()
            pending.complete(MangaColorFilterValues(red = 155))
            runCurrent()
            assertTrue(model.state.value.finished)
            assertEquals(MangaColorFilterValues(), model.state.value.values)
            assertTrue(repository.saved.isEmpty())
        }

    @Test
    fun failedReadCanRetryWithoutPrematurePersistence() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply { loadAction = { error("read failed") } }
            val model = MangaColorFilterViewModel(repository, SavedStateHandle())
            runCurrent()
            assertEquals("read failed", model.state.value.error)
            assertFalse(model.state.value.loading)
            repository.loadAction = { MangaColorFilterValues(green = 20) }
            model.load()
            runCurrent()
            assertNull(model.state.value.error)
            assertEquals(20, model.state.value.values.green)
            assertTrue(repository.saved.isEmpty())
            model.finish()
            assertEquals(listOf(MangaColorFilterValues(green = 20)), repository.saved)
        }

    @Test
    fun lateReadAfterEditBeforeDismissDoesNotResetDraft() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MangaColorFilterValues>()
            val repository = FakeRepository().apply { loadAction = { pending.await() } }
            val model = MangaColorFilterViewModel(repository, SavedStateHandle())
            runCurrent()
            model.change(MangaColorChannel.BRIGHTNESS, 44)
            pending.complete(MangaColorFilterValues(brightness = 123))
            runCurrent()
            assertEquals(44, model.state.value.values.brightness)
            assertFalse(model.state.value.loading)
            assertTrue(repository.saved.isEmpty())
        }

    private class FakeRepository(val stored: MangaColorFilterValues = MangaColorFilterValues()) :
        MangaColorFilterRepository {
        var loadCount = 0
        var loadAction: suspend () -> MangaColorFilterValues = { stored }
        val saved = mutableListOf<MangaColorFilterValues>()

        override suspend fun load(): MangaColorFilterValues {
            loadCount++
            return loadAction()
        }

        override fun save(values: MangaColorFilterValues) {
            saved += values
        }
    }
}
