package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.BackgroundBlurRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundBlurViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BackgroundBlurViewModel>()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        models.forEach { it.stop() }
        Dispatchers.resetMain()
    }

    private class Fake : BackgroundBlurRepository {
        var radius = 8
        var failLoad = false
        var failSave = false
        val writes = mutableListOf<Pair<Boolean, Int>>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun load(night: Boolean): Int {
            if (failLoad) error("read failed")
            return radius
        }

        override suspend fun save(night: Boolean, radius: Int) {
            gate?.await()
            if (failSave) error("write failed")
            writes += night to radius
        }
    }

    private fun model(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        night: Boolean = false,
    ) = BackgroundBlurViewModel(repo, saved, night).also { models += it }

    @Test
    fun editingAndCancelNeverWritePreference() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            vm.radius(12)
            runCurrent()
            assertEquals(8, vm.state.value.radius)
            vm.radius(30)
            assertEquals(25, vm.state.value.radius)
            vm.cancel()
            runCurrent()
            assertTrue(repo.writes.isEmpty())
            assertEquals(false, vm.claim())
            assertNull(vm.claim())
        }

    @Test
    fun restoredUnsavedSliderRetainsDraftWithoutReplacingStoredPreference() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            runCurrent()
            first.radius(17)
            val restored =
                model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
            runCurrent()
            assertEquals(17, restored.state.value.radius)
            assertEquals(8, repo.radius)
            assertTrue(repo.writes.isEmpty())
        }

    @Test
    fun saveDisablesDuplicateConfirmAndCancelUntilCommitThenClaimsOnceAcrossRestoration() =
        runTest(dispatcher) {
            val repo = Fake().apply { gate = CompletableDeferred() }
            val saved = SavedStateHandle()
            val vm = model(repo, saved, true)
            runCurrent()
            vm.radius(13)
            vm.save()
            vm.save()
            vm.cancel()
            runCurrent()
            assertTrue(vm.state.value.saving)
            assertFalse(vm.state.value.finished)
            repo.gate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf(true to 13), repo.writes)
            assertEquals(true, vm.claim())
            val restored =
                model(
                    repo,
                    SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                    true,
                )
            runCurrent()
            assertTrue(restored.state.value.finished)
            assertNull(restored.claim())
            assertEquals(1, repo.writes.size)
        }

    @Test
    fun loadFailureRequiresRetryBeforeAnySaveAndPreservesCancel() =
        runTest(dispatcher) {
            val repo = Fake().apply { failLoad = true }
            val vm = model(repo)
            runCurrent()
            vm.save()
            runCurrent()
            assertTrue(repo.writes.isEmpty())
            assertNotNull(vm.state.value.error)
            repo.failLoad = false
            vm.retry()
            runCurrent()
            assertEquals(8, vm.state.value.radius)
            assertNull(vm.state.value.error)
        }

    @Test
    fun saveFailureKeepsDraftAndRetryCommitsSameNightRadius() =
        runTest(dispatcher) {
            val repo = Fake().apply { failSave = true }
            val vm = model(repo, night = true)
            runCurrent()
            vm.radius(0)
            vm.save()
            runCurrent()
            assertFalse(vm.state.value.finished)
            assertNotNull(vm.state.value.error)
            assertEquals(0, vm.state.value.radius)
            repo.failSave = false
            vm.retry()
            runCurrent()
            assertEquals(listOf(true to 0), repo.writes)
            assertEquals(true, vm.claim())
        }
}
