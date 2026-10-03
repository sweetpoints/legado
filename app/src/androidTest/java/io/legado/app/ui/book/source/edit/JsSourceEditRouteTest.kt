package io.legado.app.ui.book.source.edit

import android.util.AtomicFile
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.theme.LegadoComposeTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class JsSourceEditRouteTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = FileJsSourceEditRepository(context)
    private val savedState = SavedStateHandle()
    private val store = ViewModelStore()
    private var model: JsSourceEditViewModel? = null

    @After
    fun cleanup() {
        val path = model?.state?.value?.editorPath
        store.clear()
        runBlocking { repository.release(path) }
        savedState.get<String>("jsSourceDraftId")?.let { sessionId ->
            AtomicFile(File(context.filesDir, "js-source-edit-drafts/$sessionId.json")).delete()
        }
    }

    @Test
    fun pendingLaunchWaitsForResumedAndDoesNotReplayAfterPause() {
        lateinit var owner: TestLifecycleOwner
        var launches = 0
        compose.runOnIdle {
            owner = TestLifecycleOwner()
            owner.registry.currentState = Lifecycle.State.CREATED
            model = JsSourceEditViewModel(repository, savedState, null)
            store.put("editor", model!!)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    JsSourceEditRoute(model!!, { _, _ -> launches++ }, {})
                }
            }
        }
        compose.waitUntil { model!!.state.value.loaded }
        compose.runOnIdle {
            assertEquals(0, launches)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { launches == 1 }
        compose.runOnIdle {
            assertEquals(JsSourceEditStage.EDITOR_OPEN, model!!.state.value.stage)
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, launches) }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
