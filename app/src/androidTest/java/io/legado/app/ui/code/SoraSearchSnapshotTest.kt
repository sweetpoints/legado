package io.legado.app.ui.code

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.github.rosemoe.sora.widget.LegadoCodeSearchSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SoraSearchSnapshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var editor: CodeEditor
    @Volatile private var publications = 0

    @After
    fun cleanup() {
        compose.runOnIdle { if (this::editor.isInitialized) editor.release() }
    }

    @Test
    fun acceptedNativeSearchPositionsAreImmutableUtf16Copies() {
        val raw = "😀\r\nalpha beta alpha"
        compose.setContent {
            AndroidView(
                factory = { context ->
                    CodeEditor(context).also {
                        editor = it
                        it.setText(raw)
                        it.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ ->
                            publications++
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.runOnIdle {
            editor.searcher.search(
                "(alpha)",
                SearchOptions(
                    SearchOptions.TYPE_REGULAR_EXPRESSION,
                    false,
                    RegexBackrefGrammar.DEFAULT,
                ),
            )
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            publications > 0
        }
        compose.runOnIdle {
            val snapshot = LegadoCodeSearchSnapshot.capture(editor)!!
            assertEquals(raw, snapshot.source)
            assertEquals("(alpha)", snapshot.pattern)
            assertEquals(listOf(4, 15), snapshot.regions.map { it.start })
            assertTrue(
                runCatching { snapshot.regions.clear() }.exceptionOrNull()
                    is UnsupportedOperationException
            )
            editor.text.insert(0, 0, "prefix ")
            assertEquals(raw, snapshot.source)
            assertEquals(listOf(4, 15), snapshot.regions.map { it.start })
        }
    }

    @Test
    fun newQueryCannotCapturePreviousPositionsBeforeMainAcceptsItsPublication() {
        compose.setContent {
            AndroidView(
                factory = { context ->
                    CodeEditor(context).also {
                        editor = it
                        it.setText("alpha beta alpha")
                        it.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ ->
                            publications++
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.runOnIdle {
            editor.searcher.search("alpha", SearchOptions(SearchOptions.TYPE_NORMAL, true))
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            publications > 0
        }
        compose.runOnIdle {
            editor.searcher.search("beta", SearchOptions(SearchOptions.TYPE_NORMAL, true))
            assertNull(LegadoCodeSearchSnapshot.capture(editor))
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            publications > 1
        }
        compose.runOnIdle {
            assertEquals("beta", LegadoCodeSearchSnapshot.capture(editor)!!.pattern)
            assertEquals(1, LegadoCodeSearchSnapshot.capture(editor)!!.regions.size)
        }
    }
}
