package io.legado.app.ui.rss.article

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.RssArticleRow
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class RssArticlesPageScreenTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(RssArticlesPageState())
    private var style by mutableIntStateOf(0)
    private var active by mutableStateOf(true)
    private val rows = List(40) { RssArticleRow("$it", "Title $it", null, "Date $it", false, "source") }
    private val acknowledgements = mutableListOf<Long>()
    private val positions = mutableListOf<Pair<Int, Int>>()
    private var retries = 0; private var more = 0; private var selected = ""
    private fun show() {
        compose.setContent { LegadoComposeTheme {
            RssArticlesPageScreen(state, style, false, active, false, {}, { more++ }, { retries++ },
                { selected = it }, { index, offset -> positions += index to offset }, { token ->
                    acknowledgements += token; state = state.copy(scrollRequest = 0)
                }, Modifier.height(350.dp).width(320.dp), image = { _, mod, _, _ -> Box(mod) })
        } }
    }
    @Test fun loadingAndEmptyRoomEmissionDoNotConsumeRestorationBeforeRowsArrive() {
        state = state.copy(firstVisible = 20, firstOffset = 9, scrollRequest = 11); show()
        assertTrue(acknowledgements.isEmpty()); assertTrue(positions.isEmpty())
        compose.runOnIdle { state = state.copy(loaded = true) }
        assertTrue(acknowledgements.isEmpty()); assertTrue(positions.isEmpty())
        compose.runOnIdle { state = state.copy(rows = rows) }
        compose.onNodeWithTag("rss-article-row-20").assertIsDisplayed()
        assertEquals(listOf(11L), acknowledgements)
        assertTrue(positions.any { it.first == 20 })
    }
    @Test fun hiddenPageKeepsScrollTicketUntilItBecomesActive() {
        active = false; state = state.copy(loaded = true, rows = rows, firstVisible = 15, scrollRequest = 3); show()
        assertTrue(acknowledgements.isEmpty()); assertTrue(positions.isEmpty())
        compose.runOnIdle { active = true }
        compose.onNodeWithTag("rss-article-row-15").assertIsDisplayed()
        assertEquals(listOf(3L), acknowledgements)
    }
    @Test fun fivePresentationsNavigateTheExactArticleIdentity() {
        state = state.copy(loaded = true, rows = rows.take(2)); show()
        repeat(5) { next ->
            compose.runOnIdle { style = next }
            compose.onNodeWithTag("rss-article-row-0").performClick()
            assertEquals("0", selected)
        }
    }
    @Test fun errorFooterShowsDetailsBeforeRetryAndNeverAutomaticallyRetries() {
        state = state.copy(loaded = true, error = "Diagnostic", retry = RssArticlesRetry.Refresh); show()
        assertEquals(0, retries); assertEquals(0, more)
        compose.onNodeWithTag("rss-articles-footer").performClick()
        compose.onNodeWithText("Diagnostic").assertIsDisplayed()
        compose.onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.retry)).performClick()
        assertEquals(1, retries); assertEquals(0, more)
    }
    @Test fun visibleEndRequestsMoreAndLoadingFooterCannotIssueDuplicateRequest() {
        state = state.copy(loaded = true, rows = rows.take(1), hasMore = true); show()
        compose.waitForIdle(); assertTrue(more > 0)
        compose.runOnIdle { state = state.copy(loadingMore = true) }
        val before = more
        compose.onNodeWithTag("rss-articles-footer").assertIsNotEnabled()
        compose.waitForIdle(); assertEquals(before, more)
    }
}
