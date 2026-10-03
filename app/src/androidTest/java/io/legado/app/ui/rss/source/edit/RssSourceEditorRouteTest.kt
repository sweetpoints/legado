package io.legado.app.ui.rss.source.edit

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RssSourceEditorRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<RssSourceEditorViewModel>()
    private val repos = mutableListOf<Fake>()

    @After
    fun after() {
        compose.runOnIdle {
            models.forEach {
                it.stop()
                it.viewModelScope.cancel()
            }
            repos.forEach { it.exportGate?.complete(Unit) }
        }
    }

    private fun newModel(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
    ): RssSourceEditorViewModel {
        lateinit var result: RssSourceEditorViewModel
        compose.runOnIdle {
            result = RssSourceEditorViewModel(repo, saved, "url")
            models += result
            repos += repo
        }
        return result
    }

    @Test
    fun savedActionWaitsForResumeConsumesBeforeCallbackAndDoesNotRepeatAfterPause() {
        val repo = Fake()
        val model = newModel(repo)
        val owner = Owner()
        var deliveries = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    RssSourceEditorRoute(
                        model,
                        { effect, _ ->
                            assertTrue(model.state.value.effects.isEmpty())
                            assertEquals(RssSourceEditorEffectKind.SavedDebug, effect.kind)
                            deliveries++
                        },
                        {},
                        { error(it) },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle { model.save(RssSourceEditorSaveAction.Debug) }
        compose.waitUntil { model.state.value.effects.isNotEmpty() }
        assertEquals(0, deliveries)
        assertEquals(1, repo.saves)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { deliveries == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, deliveries)
        assertEquals(1, repo.saves)
    }

    @Test
    fun consumedFinishedSnapshotClosesRestoredHostWithoutDeliveringSaveAgain() {
        val repo = Fake()
        val saved = SavedStateHandle()
        var model by mutableStateOf(newModel(repo, saved))
        var closes = 0
        var deliveries = 0
        compose.setContent {
            LegadoComposeTheme {
                RssSourceEditorRoute(
                    model,
                    { _, _ -> deliveries++ },
                    { savedResult ->
                        assertTrue(savedResult)
                        closes++
                    },
                    { error(it) },
                )
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle { model.save(RssSourceEditorSaveAction.Close) }
        compose.waitUntil { closes == 1 }
        val restoredSaved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        compose.runOnIdle {
            runBlocking { model.flush() }
            model.stop()
        }
        val restored = newModel(repo, restoredSaved)
        compose.runOnIdle { model = restored }
        compose.waitUntil { closes == 2 }
        assertEquals(0, deliveries)
        assertEquals(1, repo.saves)
        assertTrue(restored.state.value.effects.isEmpty())
    }

    @Test
    fun nonCooperativePayloadCompletingAfterPauseResumeCannotDeliverFromCanceledCollector() {
        val repo = Fake()
        repo.exportGate = CompletableDeferred()
        val model = newModel(repo)
        val owner = Owner()
        var deliveries = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    RssSourceEditorRoute(
                        model,
                        { effect, payload ->
                            assertEquals(RssSourceEditorEffectKind.Clipboard, effect.kind)
                            assertEquals("payload", payload)
                            deliveries++
                        },
                        {},
                        { error(it) },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle { model.copy() }
        compose.waitUntil { repo.exports == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { repo.exports == 2 }
        compose.runOnIdle { repo.exportGate!!.complete(Unit) }
        compose.waitUntil { deliveries == 1 }
        compose.waitForIdle()
        assertTrue(model.state.value.effects.isEmpty())
        assertEquals(1, deliveries)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle
            get() = registry
    }

    private class Fake : RssSourceEditorRepository {
        var saves = 0
        var exports = 0
        var exportGate: CompletableDeferred<Unit>? = null
        val documents = mutableMapOf<String, RssSourceEditorDocument>()

        override suspend fun load(key: String) =
            RssSourceEditorDocument(key, RssSourceEditorDraft.from(RssSource(key, "Name")))

        override suspend fun readDraft(session: String) = documents[session]

        override suspend fun writeDraft(session: String, document: RssSourceEditorDocument) {
            if ((documents[session]?.revision ?: -1) <= document.revision)
                documents[session] = document
        }

        override suspend fun save(
            session: String,
            document: RssSourceEditorDocument,
            action: RssSourceEditorSaveAction,
            autoComplete: Boolean,
        ): RssSourceEditorDocument {
            saves++
            return document
                .copy(
                    baseline = document.draft,
                    revision = document.revision + 1,
                    delivery =
                        RssSourceEditorDelivery(UUID.randomUUID().toString(), action, "url", true),
                )
                .also { writeDraft(session, it) }
        }

        override suspend fun parse(text: String): RssSourceEditorDraft? = null

        override suspend fun export(
            document: RssSourceEditorDocument,
            autoComplete: Boolean,
        ): String =
            withContext(NonCancellable) {
                exports++
                exportGate?.await()
                "payload"
            }

        override suspend fun clearCookie(url: String) = Unit

        override suspend fun variable(key: String): String? = null

        override suspend fun setVariable(key: String, value: String?) = Unit

        override suspend fun editorInput(text: String) = "path"

        override suspend fun editorText(path: String) = "text"

        override suspend fun clearEditor(vararg paths: String?) = Unit
    }
}
