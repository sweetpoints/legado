package io.legado.app.ui.main.rss

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class MainRssScreenTest {
    @get:Rule val compose = createComposeRule()
    private val rows = (0..99).map { MainRssRow("id-$it", "origin-$it", "Feed $it", null, it % 2 == 0) }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val images = object : RssArticleImageRepository {
        override suspend fun ratio(source: String): Float? = null
        override suspend fun load(source: String, origin: String, width: Int, height: Int, natural: Boolean): AnimatedDrawableResource? = null
    }
    private fun actions(query: (String, Int, Int) -> Unit = { _, _, _ -> }, action: (MainRssAction, String?) -> Unit = { _, _ -> },
        top: (String) -> Unit = {}, disable: (String) -> Unit = {}, delete: (String) -> Unit = {}, scroll: (Int, Int) -> Unit = { _, _ -> }) =
        MainRssActions(query, action, top, disable, delete, {}, {}, {}, scroll)
    private fun show(state: MainRssState, actions: MainRssActions = actions()) {
        compose.setContent { LegadoComposeTheme { MainRssScreen(state, actions, images) } }
    }
    @Test fun headerOccupiesFirstCellOfOriginalFourColumnGrid() {
        show(MainRssState(loaded = true, active = true, rows = rows.take(4)))
        val header = compose.onNodeWithTag("main-rss-subscriptions").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("main-rss-card-id-0").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("main-rss-card-id-2").fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithTag("main-rss-card-id-3").fetchSemanticsNode().boundsInRoot
        assertEquals(header.top, first.top, 1f); assertEquals(header.top, third.top, 1f)
        assertTrue(header.left < first.left && first.left < third.left); assertTrue(fourth.top > header.top)
    }
    @Test fun cardsAndAllFourToolbarActionsKeepTheirExactTargets() {
        val calls = mutableListOf<Pair<MainRssAction, String?>>()
        show(MainRssState(loaded = true, active = true, rows = rows.take(1)), actions(action = { kind, id -> calls += kind to id }))
        compose.onNodeWithTag("main-rss-subscriptions").performClick()
        compose.onNodeWithTag("main-rss-card-id-0").performClick()
        compose.onNodeWithTag("main-rss-history").performClick()
        compose.onNodeWithTag("main-rss-favorites").performClick()
        compose.onNodeWithTag("main-rss-settings").performClick()
        assertEquals(listOf(MainRssAction.Subscriptions to null, MainRssAction.Open to "id-0", MainRssAction.History to null,
            MainRssAction.Favorites to null, MainRssAction.Settings to null), calls)
    }
    @Test fun longPressMenuKeepsOrderLoginEligibilityAndMutationCallbacks() {
        val calls = mutableListOf<String>()
        show(MainRssState(loaded = true, active = true, rows = rows.take(2)), actions(top = { calls += "top:$it" },
            disable = { calls += "disable:$it" }, delete = { calls += "delete:$it" }))
        fun open(id: String) = compose.onNodeWithTag("main-rss-card-$id").performTouchInput { longClick() }
        open("id-0")
        val positions = listOf("edit", "top", "login", "disable", "delete").map {
            compose.onNodeWithTag("main-rss-$it-id-0").fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(positions.sorted(), positions)
        compose.onNodeWithTag("main-rss-top-id-0").performClick()
        open("id-1"); compose.onNodeWithTag("main-rss-login-id-1").assertDoesNotExist()
        compose.onNodeWithTag("main-rss-disable-id-1").performClick()
        open("id-0"); compose.onNodeWithTag("main-rss-delete-id-0").performClick()
        assertEquals(listOf("top:id-0", "disable:id-1", "delete:id-0"), calls)
    }
    @Test fun groupsSubmitExactPrefixAndSearchPreservesCursor() {
        val queries = mutableListOf<Triple<String, Int, Int>>()
        show(MainRssState(loaded = true, active = true, groups = listOf("A")), actions(query = { text, start, end -> queries += Triple(text, start, end) }))
        compose.onNodeWithTag("main-rss-groups").performClick(); compose.onNodeWithText("A").performClick()
        assertEquals(Triple("group:A", 7, 7), queries.single())
        compose.onNodeWithTag("main-rss-search").performTextReplacement("typed query")
        compose.onNodeWithTag("main-rss-search").performTextInputSelection(androidx.compose.ui.text.TextRange(2, 5))
        assertEquals(Triple("typed query", 2, 5), queries.last())
    }
    @Test fun inactiveScreenSuppressesRestoredConfirmationAndRejectsClicksUntilVisible() {
        var state by mutableStateOf(MainRssState(loaded = true, active = false, deletingId = "id-0", deletingName = "Feed"))
        compose.setContent { LegadoComposeTheme { MainRssScreen(state, actions(), images) } }
        compose.onNodeWithTag("main-rss-delete-confirm").assertDoesNotExist()
        compose.onNodeWithTag("main-rss-settings").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(active = true) }
        compose.onNodeWithTag("main-rss-delete-confirm").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sure_del) + "\nFeed").assertIsDisplayed()
    }
    @Test fun restoredGridScrollWaitsForRoomRowsAndDoesNotWriteZeroDuringLoading() {
        var state by mutableStateOf(MainRssState(scrollIndex = 40, scrollOffset = 3))
        val observed = mutableListOf<Int>()
        compose.setContent { LegadoComposeTheme { MainRssScreen(state, actions(scroll = { index, _ -> observed += index }), images) } }
        compose.waitForIdle(); assertTrue(observed.isEmpty())
        compose.runOnIdle { state = state.copy(loaded = true, active = true, rows = rows) }
        compose.onNodeWithTag("main-rss-card-id-39").assertIsDisplayed()
        assertFalse(observed.contains(0))
    }
}
