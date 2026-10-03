package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourceCheckSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<SourceCheckSettingsViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        models.forEach { it.stop() }
        Dispatchers.resetMain()
    }

    private class Fake : SourceCheckSettingsRepository {
        var initial = SourceCheckSettings(180000, true, false, true, true, true, true, true)
        val saves = mutableListOf<SourceCheckSettings>()
        var fail = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun load() = initial

        override suspend fun save(settings: SourceCheckSettings) {
            gate?.await()
            if (fail) error("disk failure")
            saves += settings
        }
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        SourceCheckSettingsViewModel(repo, saved).also { models += it }

    @Test
    fun dependentChecksClearDownstreamAndFallbackKeepsAtLeastOneEntryWithoutSaving() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            model.toggle(SourceCheckOption.Info)
            assertFalse(model.state.value.settings!!.category)
            assertFalse(model.state.value.settings!!.content)
            model.toggle(SourceCheckOption.Content)
            assertFalse(model.state.value.settings!!.content)
            model.toggle(SourceCheckOption.Search)
            model.toggle(SourceCheckOption.Discovery)
            assertTrue(model.state.value.settings!!.search)
            assertFalse(model.state.value.settings!!.discovery)
            assertFalse(model.state.value.infoEnabled)
            assertTrue(repo.saves.isEmpty())
            model.toggle(SourceCheckOption.Discovery)
            assertTrue(model.state.value.infoEnabled)
        }

    @Test
    fun domainOnlyClearsInfoAndUncheckingDomainRestoresSearch() =
        runTest(dispatcher) {
            val model = model(Fake())
            runCurrent()
            model.toggle(SourceCheckOption.Domain)
            model.toggle(SourceCheckOption.Search)
            model.toggle(SourceCheckOption.Discovery)
            assertFalse(model.state.value.settings!!.search)
            assertFalse(model.state.value.settings!!.discovery)
            assertFalse(model.state.value.settings!!.info)
            model.toggle(SourceCheckOption.Domain)
            assertTrue(model.state.value.settings!!.search)
        }

    @Test
    fun invalidAndOverflowingSecondsNeverReachPersistenceAndMaximumSafeValueSaves() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            for (input in
                listOf(
                    "",
                    "0",
                    Long.MAX_VALUE.toString(),
                    (Long.MAX_VALUE / 1000 + 1).toString(),
                )) {
                model.seconds(input)
                model.save()
                runCurrent()
                assertNotNull(model.state.value.timeoutIssue)
            }
            assertTrue(repo.saves.isEmpty())
            model.seconds((Long.MAX_VALUE / 1000).toString())
            model.save()
            runCurrent()
            assertEquals(Long.MAX_VALUE / 1000 * 1000, repo.saves.single().timeout)
        }

    @Test
    fun restoredUnsavedDraftPreservesSwitchDependenciesAndBoundedInputWithoutWriting() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = model(repo, saved)
            runCurrent()
            model.seconds("12")
            model.toggle(SourceCheckOption.Info)
            val restore =
                model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
            runCurrent()
            assertEquals("12", restore.state.value.seconds)
            assertFalse(restore.state.value.categoryEnabled)
            restore.seconds("9".repeat(100000))
            assertEquals("12", restore.state.value.seconds)
            assertTrue(repo.saves.isEmpty())
        }

    @Test
    fun duplicateSaveIsBlockedAndFailedSaveAllowsCorrectionAndRetry() =
        runTest(dispatcher) {
            val repo =
                Fake().apply {
                    gate = CompletableDeferred()
                    fail = true
                }
            val model = model(repo)
            runCurrent()
            model.seconds("20")
            model.save()
            model.save()
            model.seconds("30")
            runCurrent()
            assertEquals("20", model.state.value.seconds)
            repo.gate!!.complete(Unit)
            runCurrent()
            assertFalse(model.state.value.saving)
            assertNotNull(model.state.value.error)
            repo.fail = false
            model.seconds("30")
            model.save()
            runCurrent()
            assertEquals(30000L, repo.saves.single().timeout)
        }
}
