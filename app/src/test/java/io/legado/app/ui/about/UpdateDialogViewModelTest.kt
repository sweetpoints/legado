package io.legado.app.ui.about

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDialogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<UpdateDialogViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake(
        var request: UpdateDialogRequest =
            UpdateDialogRequest(
                "v1",
                "## Update\n**Bold** <i>HTML</i>\n\n| A | B |\n|---|---|\n| C | D |",
                "primary",
                "app.apk",
                "backup",
                "mirror",
                "alternate",
            )
    ) : UpdateDialogRepository {
        var gate: CompletableDeferred<Unit>? = null
        var ignoreGate: CompletableDeferred<Unit>? = null
        var uncooperative = false
        var fail = false
        var loads = 0
        var ignores = mutableListOf<String>()

        override suspend fun load(id: String): UpdateDialogRequest {
            loads++
            if (uncooperative) withContext(NonCancellable) { gate?.await() } else gate?.await()
            if (fail) error("failed")
            return request
        }

        override suspend fun ignore(version: String) {
            ignoreGate?.await()
            if (fail) error("failed")
            ignores += version
        }
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        UpdateDialogViewModel(repo, saved, "id", dispatcher).also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                runCurrent()
            }
        }

    @Test
    fun pendingRequestDisablesActionsAndFailedLoadingCanRetry() = test {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                fail = true
            }
        val model = model(repo)
        runCurrent()
        model.download()
        model.ignore()
        model.browser()
        assertNull(model.state.value.effect)
        assertTrue(repo.ignores.isEmpty())
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals("failed", model.state.value.error)
        repo.fail = false
        model.load()
        runCurrent()
        assertTrue(model.state.value.canAct)
        assertEquals(2, repo.loads)
        assertTrue(model.state.value.document.text.contains("Bold HTML"))
        assertFalse(model.state.value.document.text.contains("<i>"))
    }

    @Test
    fun formalTargetsStayDistinctAndBrowserPrefersBackupWhileBlankAlternatesAreHidden() = test {
        val model = model(Fake())
        runCurrent()
        assertEquals(
            listOf(
                UpdateDownloadTarget.Backup,
                UpdateDownloadTarget.Mirror,
                UpdateDownloadTarget.AlternateMirror,
            ),
            model.state.value.downloadTargets,
        )
        model.download(UpdateDownloadTarget.Mirror)
        assertEquals("mirror", model.state.value.effect!!.url)
        assertEquals("app.apk", model.state.value.effect!!.fileName)
        val first = model.state.value.effect!!.id
        model.failed(first, "service unavailable")
        model.download(UpdateDownloadTarget.AlternateMirror)
        val second = model.state.value.effect!!
        assertTrue(second.id > first)
        model.delivered(first)
        assertEquals(second, model.state.value.effect)
        model.failed(second.id, "fail")
        model.browser()
        assertEquals("backup", model.state.value.effect!!.url)
        val missing =
            model(Fake(UpdateDialogRequest("v", "log", "primary", "app.apk", backupUrl = " ")))
        runCurrent()
        assertTrue(missing.state.value.downloadTargets.isEmpty())
        missing.download(UpdateDownloadTarget.Backup)
        assertNull(missing.state.value.effect)
        missing.browser()
        assertEquals("primary", missing.state.value.effect!!.url)
    }

    @Test
    fun queuedDownloadSurvivesRestoreAndAcknowledgementPreventsDuplicateHandoff() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.download()
        val pending = first.state.value.effect!!
        first.download()
        assertEquals(pending, first.state.value.effect)
        val restoredSaved = copy(saved)
        val restored = model(repo, restoredSaved)
        runCurrent()
        assertEquals(pending, restored.state.value.effect)
        restored.delivered(pending.id)
        assertTrue(restored.state.value.finished)
        assertNull(restored.state.value.effect)
        val again = model(repo, copy(restoredSaved))
        runCurrent()
        assertTrue(again.state.value.finished)
        assertEquals(2, repo.loads)
    }

    @Test
    fun browserAcknowledgementKeepsDialogOpenAndAllowsNextUpdateWithFreshToken() = test {
        val model = model(Fake())
        runCurrent()
        model.browser()
        val browser = model.state.value.effect!!
        model.delivered(browser.id)
        assertFalse(model.state.value.finished)
        assertTrue(model.state.value.canAct)
        model.download()
        assertTrue(model.state.value.effect!!.id > browser.id)
    }

    @Test
    fun ignorePersistsOnceAndRequiresNoticeDeliveryBeforeClosing() = test {
        val repo = Fake().apply { ignoreGate = CompletableDeferred() }
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        model.ignore()
        model.ignore()
        model.cancel()
        runCurrent()
        assertTrue(model.state.value.busy)
        assertFalse(model.state.value.finished)
        repo.ignoreGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("v1"), repo.ignores)
        val restored = model(repo, copy(saved))
        runCurrent()
        val effect = restored.state.value.effect!!
        assertEquals(UpdateDialogAction.IgnoredNotice, effect.action)
        restored.delivered(effect.id)
        assertTrue(restored.state.value.finished)
        assertEquals(1, repo.ignores.size)
    }

    @Test
    fun cancellationClearsPendingNativeActionAndRejectsUncooperativeLateLoad() = test {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                uncooperative = true
            }
        val model = model(repo)
        runCurrent()
        model.cancel()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.finished)
        assertNull(model.state.value.request)
        val loaded = model(Fake())
        runCurrent()
        loaded.download()
        loaded.cancel()
        assertNull(loaded.state.value.effect)
        assertTrue(loaded.state.value.finished)
    }

    @Test
    fun invalidDownloadInputsCannotQueueServiceActionAndIgnoreFailureCanRetry() = test {
        val blank = model(Fake(UpdateDialogRequest("v", "log", "url", " ")))
        runCurrent()
        blank.download()
        assertNull(blank.state.value.effect)
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        repo.fail = true
        model.ignore()
        runCurrent()
        assertEquals("failed", model.state.value.error)
        assertFalse(model.state.value.busy)
        repo.fail = false
        model.ignore()
        runCurrent()
        assertEquals(1, repo.ignores.size)
    }

    @Test
    fun metadataAndLargeBodyStayOutOfSavedState() = test {
        assertEquals(
            "1 kb · 2024-01-01",
            formatUpdateMetadata(1024, 1704067200000, ZoneId.of("UTC")),
        )
        assertEquals("", formatUpdateMetadata(0, 0))
        val saved = SavedStateHandle()
        val request = UpdateDialogRequest("v", "Long ".repeat(50000), "url", "app.apk", size = 1024)
        val model = model(Fake(request), saved)
        runCurrent()
        model.download()
        assertEquals(request.body, model.state.value.request!!.body)
        assertTrue(
            saved
                .keys()
                .mapNotNull { saved.get<Any?>(it) }
                .filterIsInstance<String>()
                .all { it.length < 100 }
        )
    }
}
