package io.legado.app.ui.code

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.text.Cursor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CodeEditorEngineTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var engine: CodeEditorEngine
    @Volatile private var status = CodeEditorEngineStatus()

    @After
    fun cleanup() {
        compose.runOnIdle { if (this::engine.isInitialized) engine.dispose() }
    }

    @Test
    fun realSoraKeepsTextMateRawCodeDirectionAndNativeUndoRedo() {
        val raw = "😀\r\nalpha beta alpha"
        val session = CodeEditorSession(raw, selection = CodeEditorSelection(raw.length, 4))
        val language =
            CodeEditorLanguageEngine(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { language.prepare(session) }
        compose.setContent {
            AndroidView(
                factory = { context ->
                    SoraCodeEditorEngine(context, session, language, {}, { status = it })
                        .also { engine = it }
                        .view
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.waitUntil(10_000) { status.ready }
        compose.runOnIdle {
            val sora = engine as SoraCodeEditorEngine
            assertTrue(sora.view.editorLanguage is RuntimeObjectCompletionLanguage)
            assertEquals(Cursor.DIRECTION_RTL, sora.view.cursor.selectionDirection)
            engine.snapshot { assertEquals(raw, it.text) }
            sora.view.text.insert(0, 0, "prefix ")
            engine.undo()
            engine.snapshot { assertEquals(raw, it.text) }
            engine.redo()
            engine.snapshot { assertEquals("prefix $raw", it.text) }
            assertFalse(sora.view.isSaveEnabled)
        }
    }

    @Test
    fun realSoraRegexReplacementRetainsDefaultBackReferences() {
        val session = CodeEditorSession("alpha beta alpha")
        val language =
            CodeEditorLanguageEngine(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { language.prepare(session) }
        compose.setContent {
            AndroidView(
                factory = { context ->
                    SoraCodeEditorEngine(context, session, language, {}, { status = it })
                        .also { engine = it }
                        .view
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.waitUntil(10_000) { status.ready }
        compose.runOnIdle {
            (engine as SoraCodeEditorEngine).search(
                CodeEditorSearch(visible = true, query = "(alpha)")
            )
        }
        compose.waitUntil(10_000) { status.searchResult.substringAfter('/') == "2" }
        compose.runOnIdle {
            (engine as SoraCodeEditorEngine).replaceAll("$1!")
        }
        compose.waitUntil(10_000) { !status.replacing }
        compose.runOnIdle {
            engine.snapshot { assertEquals("alpha! beta alpha!", it.text) }
            engine.undo()
            engine.snapshot { assertEquals("alpha beta alpha", it.text) }
        }
    }

    @Test
    fun retiredNativeOwnerCannotApplyGatedRegexReplacement() {
        val session = CodeEditorSession("😀\r\nalpha")
        val language =
            CodeEditorLanguageEngine(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { language.prepare(session) }
        val currentOwner = AtomicBoolean(true)
        val started = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        compose.setContent {
            AndroidView(
                factory = { context ->
                    SoraCodeEditorEngine(
                            context,
                            session,
                            language,
                            {},
                            { status = it },
                            isCurrentOwner = currentOwner::get,
                            calculateReplacement = { request, replacement ->
                                started.set(true)
                                gate.await()
                                replaceCodeEditorMatches(request, replacement).also {
                                    finished.set(true)
                                }
                            },
                        )
                        .also { engine = it }
                        .view
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.runOnIdle {
            (engine as SoraCodeEditorEngine).search(
                CodeEditorSearch(visible = true, query = "(alpha)")
            )
        }
        compose.waitUntil(10_000) { status.searchResult.substringAfter('/') == "1" }
        compose.runOnIdle { (engine as SoraCodeEditorEngine).replaceAll("$1!") }
        compose.waitUntil(10_000) { started.get() }
        compose.runOnIdle { currentOwner.set(false) }
        gate.complete(Unit)
        compose.waitUntil(10_000) { finished.get() }
        compose.runOnIdle {
            assertEquals(session.text, (engine as SoraCodeEditorEngine).view.text.toString())
        }
    }

    @Test
    fun realSafeWebViewPreservesCleanCrLfAndCancelsLateSnapshotGeneration() {
        val raw = "😀\r\n" + "e\u0301".repeat(100)
        val session = CodeEditorSession(raw, selection = CodeEditorSelection(raw.length))
        compose.setContent {
            AndroidView(
                factory = { context ->
                    SafeCodeEditorEngine(context, session, {}, { status = it })
                        .also { engine = it }
                        .view
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.waitUntil(15_000) { status.ready }
        var snapshots = 0
        compose.runOnIdle {
            engine.snapshot { snapshots++ }
            engine.cancelRead()
        }
        var barrier = false
        compose.runOnIdle {
            (engine as SafeCodeEditorEngine).view.evaluateJavascript("1") { barrier = true }
        }
        compose.waitUntil(10_000) { barrier }
        assertEquals(0, snapshots)
        compose.runOnIdle {
            engine.snapshot {
                assertEquals(raw, it.text)
                assertEquals(CodeEditorSelection(raw.length), it.selection)
                snapshots++
            }
        }
        compose.waitUntil(10_000) { snapshots == 1 }
        compose.runOnIdle {
            assertFalse(engine.view.isSaveEnabled)
            engine.restoreEditing()
        }
    }
}
