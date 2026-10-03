package io.legado.app.ui.about

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDialogDownloadContractTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private val repo =
        object : UpdateDialogRepository {
            override suspend fun load(id: String) =
                UpdateDialogRequest(
                    "beta",
                    "Log",
                    "primary",
                    "app.apk",
                    "backup",
                    "mirror",
                    "alternate",
                    beta = true,
                )

            override suspend fun ignore(version: String) {
                error("Beta cannot ignore")
            }
        }

    @Test
    fun betaUpdateQueuesNativeDownloadAndHidesAlternateTargets() =
        runTest(dispatcher) {
            val model = UpdateDialogViewModel(repo, SavedStateHandle(), "id", dispatcher)
            try {
                runCurrent()
                model.download(UpdateDownloadTarget.Backup)
                assertNull(model.state.value.effect)
                model.ignore()
                assertNull(model.state.value.effect)
                assertTrue(model.state.value.downloadTargets.isEmpty())
                model.download()
                val effect = model.state.value.effect!!
                assertEquals(UpdateDialogAction.Download, effect.action)
                assertEquals("primary", effect.url)
                assertEquals("app.apk", effect.fileName)
            } finally {
                model.stop()
                runCurrent()
            }
        }

    @Test
    fun betaBrowserFallbackUsesPrimaryAndKeepsUpdateOpen() =
        runTest(dispatcher) {
            val model = UpdateDialogViewModel(repo, SavedStateHandle(), "id", dispatcher)
            try {
                runCurrent()
                model.browser()
                val effect = model.state.value.effect!!
                assertEquals(UpdateDialogAction.Browser, effect.action)
                assertEquals("primary", effect.url)
                model.delivered(effect.id)
                assertFalse(model.state.value.finished)
            } finally {
                model.stop()
                runCurrent()
            }
        }
}
