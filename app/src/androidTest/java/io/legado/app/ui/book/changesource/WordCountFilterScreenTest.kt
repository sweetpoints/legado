package io.legado.app.ui.book.changesource

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WordCountFilterScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        model: WordCountFilterViewModel,
        changed: (Boolean) -> Unit = {},
        close: () -> Unit = {},
    ) {
        compose.setContent { LegadoComposeTheme { WordCountFilterRoute(model, changed, close) } }
    }

    @Test
    fun relativeRangeEditsActualFieldsAndDispatchesOneRefresh() {
        val repo = Repository()
        val model = WordCountFilterViewModel(repo, SavedStateHandle())
        val notifications = mutableListOf<Boolean>()
        show(model, notifications::add)
        compose.onNodeWithTag("word-count-mode-2").performClick()
        compose.onNodeWithTag("word-count-minimum").performTextReplacement("80")
        compose.onNodeWithTag("word-count-maximum").performTextReplacement("140")
        compose.onNodeWithTag("word-count-confirm").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(WordCountFilterSettings(2, 80, 140), repo.current)
            assertEquals(listOf(true), notifications)
        }
    }

    @Test
    fun invalidInputStaysOpenAndCancellationNeverCommits() {
        val repo = Repository()
        val model = WordCountFilterViewModel(repo, SavedStateHandle())
        var closes = 0
        show(model, close = { closes++ })
        compose.onNodeWithTag("word-count-mode-1").performClick()
        compose.onNodeWithTag("word-count-minimum").performTextReplacement("6000")
        compose.onNodeWithTag("word-count-confirm").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(model.state.value.invalid)
            assertEquals(0, closes)
            assertEquals(0, repo.writes)
        }
        compose.onNodeWithTag("word-count-cancel").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(closes > 0)
            assertEquals(0, repo.writes)
        }
    }

    @Test
    fun restoredDraftDisplaysWithoutOverwritingPreferences() {
        val repo = Repository()
        val model =
            WordCountFilterViewModel(
                repo,
                SavedStateHandle(
                    mapOf("wordCount.mode" to 2, "wordCount.min" to "85", "wordCount.max" to "145")
                ),
            )
        show(model)
        compose.onNodeWithTag("word-count-minimum").assertTextContains("85")
        compose.onNodeWithTag("word-count-maximum").assertTextContains("145")
        compose.runOnIdle { assertEquals(0, repo.writes) }
    }

    private class Repository : WordCountFilterRepository {
        var current = WordCountFilterSettings()
        var writes = 0

        override fun load() = current

        override fun save(settings: WordCountFilterSettings) {
            current = settings
            writes++
        }
    }
}
