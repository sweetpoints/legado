package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakEngineViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun selectionIsDraftAndRestoresWithoutApplying() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = SpeakEngineViewModel(repo, saved)
            runCurrent()
            model.selectHttp(1)
            val restored =
                SpeakEngineViewModel(
                    repo,
                    SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                )
            runCurrent()
            assertEquals("1", restored.state.value.selection)
            assertTrue(repo.applied.isEmpty())
            restored.apply(false)
            runCurrent()
            assertEquals(listOf("1" to false), repo.applied)
            restored.apply(true)
            runCurrent()
            assertEquals(1, repo.applied.size)
        }

    @Test
    fun systemChoiceUsesLegacyJsonAndGeneralScope() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = SpeakEngineViewModel(repo, SavedStateHandle())
            runCurrent()
            model.selectSystem(SpeakSystemEngine("android.engine", "Engine"))
            assertEquals("android.engine", model.state.value.systemName)
            model.apply(true)
            runCurrent()
            assertEquals(true, repo.applied.single().second)
            assertTrue(model.state.value.finished)
            assertEquals(SpeakEngineAction.Applied, model.state.value.pending.single().action)
        }

    @Test
    fun everyHttpSelectionOffersLoginAgainAndLongPressOnlyEligible() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = SpeakEngineViewModel(repo, SavedStateHandle())
            runCurrent()
            model.selectHttp(1)
            model.selectHttp(1)
            model.login(1)
            model.login(2)
            model.selectHttp(2)
            assertEquals(3, model.state.value.pending.size)
            assertTrue(
                model.state.value.pending.all {
                    it.action == SpeakEngineAction.Login && it.argument == "1"
                }
            )
            assertEquals("2", model.state.value.selection)
        }

    @Test
    fun exportTracksStableIdAcrossListReorderAndSystemCannotExport() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = SpeakEngineViewModel(repo, SavedStateHandle())
            runCurrent()
            model.selectHttp(2)
            repo.engines.value = repo.engines.value.reversed()
            runCurrent()
            model.export(false)
            val event = model.state.value.pending.single()
            assertEquals("2", event.argument)
            assertEquals("httpTts_2.json", model.exportData(event.argument)?.name)
            model.consume(event.id)
            model.selectSystem(SpeakSystemEngine("", "系统默认"))
            model.export(false)
            assertEquals(SpeakEngineAction.SystemExport, model.state.value.pending.single().action)
            model.export(true)
            assertEquals("", model.state.value.pending.last().argument)
        }

    @Test
    fun deleteRequiresConfirmationAndCancelledDeleteDoesNothing() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = SpeakEngineViewModel(repo, SavedStateHandle())
            runCurrent()
            model.requestDelete(1)
            model.requestDelete(null)
            model.confirmDelete()
            runCurrent()
            assertTrue(repo.deleted.isEmpty())
            model.requestDelete(2)
            model.confirmDelete()
            runCurrent()
            assertEquals(listOf(2L), repo.deleted)
            assertNull(model.state.value.deleteId)
        }

    @Test
    fun rawJsonImportsRemainAllowedWhileOnlyUrlsEnterHistory() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = SpeakEngineViewModel(repo, SavedStateHandle())
            runCurrent()
            model.openOnline(true)
            model.input("{\"name\":\"TTS\"}")
            model.confirmOnline()
            runCurrent()
            assertEquals("{\"name\":\"TTS\"}", model.state.value.pending.single().argument)
            assertTrue(repo.savedHistories.isEmpty())
            model.input("https://example.com/tts.json")
            model.confirmOnline()
            runCurrent()
            model.confirmOnline()
            runCurrent()
            assertEquals(1, repo.savedHistories.size)
            model.removeHistory("https://example.com/tts.json")
            runCurrent()
            assertTrue(model.state.value.histories.isEmpty())
        }

    @Test
    fun pendingEventsRestoreAndConsumptionDoesNotReplay() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = SpeakEngineViewModel(repo, saved)
            runCurrent()
            model.edit(2)
            model.local()
            val event = model.state.value.pending.first()
            model.consume(event.id)
            val restored =
                SpeakEngineViewModel(
                    repo,
                    SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                )
            runCurrent()
            assertEquals(
                SpeakEngineAction.ImportLocal,
                restored.state.value.pending.single().action,
            )
            restored.consume(restored.state.value.pending.single().id)
            assertTrue(restored.state.value.pending.isEmpty())
        }

    @Test
    fun exportResultAndPassphraseRemainUntilExplicitClose() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = SpeakEngineViewModel(repo, SavedStateHandle())
            runCurrent()
            model.exported("https://example.com/file.json")
            runCurrent()
            assertEquals("summary", model.state.value.share?.summary)
            model.passphrase()
            runCurrent()
            assertEquals("phrase", model.state.value.share?.passphrase)
            model.closeShare()
            assertNull(model.state.value.share)
        }

    @Test
    fun cacheAndDefaultActionsInvokeRepositoryAndCacheCompletionEvent() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = SpeakEngineViewModel(repo, SavedStateHandle())
            runCurrent()
            model.importDefault()
            model.clearCache()
            model.clearCacheData()
            runCurrent()
            assertEquals(1, repo.defaults)
            assertEquals(1, repo.clears)
            assertEquals(
                listOf(SpeakEngineAction.ClearCache, SpeakEngineAction.CacheCleared),
                model.state.value.pending.map { it.action },
            )
        }

    private class Fake : SpeakEngineRepository {
        override val engines =
            MutableStateFlow(
                listOf(SpeakHttpEngine(1, "Login", true), SpeakHttpEngine(2, "Normal", false))
            )
        val applied = mutableListOf<Pair<String?, Boolean>>()
        val deleted = mutableListOf<Long>()
        val savedHistories = mutableListOf<List<String>>()
        var defaults = 0
        var clears = 0

        override fun initialSelection(): String? = null

        override suspend fun systemEngines() = listOf(SpeakSystemEngine("android.engine", "Engine"))

        override suspend fun apply(selection: String?, general: Boolean) {
            applied += selection to general
        }

        override suspend fun delete(id: Long) {
            deleted += id
        }

        override suspend fun importDefault() {
            defaults++
        }

        override suspend fun clearCache() {
            clears++
        }

        override suspend fun histories() = emptyList<String>()

        override suspend fun saveHistories(values: List<String>) {
            savedHistories += values
        }

        override suspend fun export(id: Long?) =
            SpeakEngineExport("httpTts_$id.json", byteArrayOf())

        override suspend fun share(url: String) = SpeakEngineShare(url, "summary")

        override suspend fun passphrase(url: String) = "phrase"
    }
}
