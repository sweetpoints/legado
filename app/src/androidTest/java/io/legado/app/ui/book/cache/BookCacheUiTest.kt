package io.legado.app.ui.book.cache

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.*
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import org.junit.*
import org.junit.Assert.*

class BookCacheUiTest {
    @get:Rule val compose = createComposeRule()
    private fun state() = BookCacheState(loading = false, preferencesLoaded = true, groups = listOf(BookCacheGroup(1, "One"), BookCacheGroup(2, "Two")), rows = listOf(
        BookCacheRow(BookCacheItem("remote", "Remote", "Author", false, 20, 4, 19), setOf("chapter")),
        BookCacheRow(BookCacheItem("local", "Local", "Writer", true, 8, 0, 7))))
    @Test fun longPressShowsAfterThenAllInVerticalOrderAndEachActionDeliversExactDownloadMode() {
        val calls = mutableListOf<Boolean>(); var state by mutableStateOf(state())
        compose.setContent { MaterialTheme { BookCacheScreen(state, BookCacheActions(download = { calls += it })) } }
        compose.onNodeWithTag("book-cache-download").performTouchInput { longClick() }
        val after = compose.onNodeWithTag("book-cache-download-after").fetchSemanticsNode().boundsInRoot
        val all = compose.onNodeWithTag("book-cache-download-all").fetchSemanticsNode().boundsInRoot
        assertTrue(after.bottom <= all.top)
        compose.onNodeWithTag("book-cache-download-all").performClick(); assertEquals(listOf(false), calls)
        compose.onNodeWithTag("book-cache-download").performClick(); assertEquals(listOf(false, true), calls)
        compose.runOnIdle { state = state.copy(running = true) }; compose.onNodeWithTag("book-cache-download").assertContentDescriptionEquals(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.stop))
    }
    @Test fun localRowsHideDownloadAndExportUsesStableBookKeyAndMessagesTakePriorityOverProgress() {
        val keys = mutableListOf<String?>(); val toggles = mutableListOf<String>()
        val value = state().let { it.copy(rows = listOf(it.rows.first().copy(progress = 10, message = "Done"), it.rows.last())) }
        compose.setContent { MaterialTheme { BookCacheScreen(value, BookCacheActions(export = { keys += it }, toggle = { toggles += it })) } }
        compose.onNodeWithTag("book-cache-toggle-local").assertDoesNotExist(); compose.onNodeWithTag("book-cache-toggle-remote").performClick()
        compose.onNodeWithTag("book-cache-export-local").performClick(); compose.onNodeWithTag("book-cache-message-remote").assertTextEquals("Done")
        compose.onNodeWithTag("book-cache-progress-remote").assertDoesNotExist(); assertEquals(listOf("local"), keys); assertEquals(listOf("remote"), toggles)
    }
    @Test fun groupMenuUsesStableIdAndPdfTypeSelectionIsARealPreferenceChange() {
        var value by mutableStateOf(state()); var group = 0L
        val actions = BookCacheActions(group = { group = it }, preferences = { value = value.copy(preferences = it(value.preferences)) })
        compose.setContent { MaterialTheme { BookCacheScreen(value, actions) } }
        compose.onNodeWithTag("book-cache-groups").performClick(); compose.onNodeWithTag("book-cache-group-2").performClick(); assertEquals(2L, group)
        compose.onNodeWithTag("book-cache-menu").performClick(); compose.onNodeWithTag("book-cache-type").performScrollTo().performClick()
        compose.onNodeWithTag("book-cache-type-pdf").performClick(); assertEquals("pdf", value.preferences.exportType)
    }
    @Test fun settingsDraftSurvivesRecreationAndSaveWritesExactlyOneCharsetChange() {
        var value by mutableStateOf(state()); var writes = 0
        val tester = StateRestorationTester(compose)
        val actions = BookCacheActions(openSettings = { kind -> value = value.copy(settings = BookCacheSettings("ticket", kind, value.preferences.charset)) },
            settings = { text -> value = value.copy(settings = value.settings!!.copy(draft = text)) },
            saveSettings = { writes++; value = value.copy(preferences = value.preferences.copy(charset = value.settings!!.draft), settings = null) },
            cancelSettings = { value = value.copy(settings = null) })
        tester.setContent { MaterialTheme { BookCacheScreen(value, actions) } }
        compose.onNodeWithTag("book-cache-menu").performClick(); compose.onNodeWithTag("book-cache-charset").performScrollTo().performClick()
        compose.onNodeWithTag("book-cache-setting-draft").performTextReplacement("GB18030")
        tester.emulateSavedInstanceStateRestore(); compose.onNodeWithTag("book-cache-setting-draft").assertTextContains("GB18030")
        compose.onNodeWithTag("book-cache-setting-save").performClick(); assertEquals("GB18030", value.preferences.charset); assertEquals(1, writes)
        compose.onNodeWithTag("book-cache-menu").performClick(); compose.onNodeWithTag("book-cache-charset").performScrollTo().performClick()
        compose.onNodeWithTag("book-cache-setting-draft").performTextReplacement("discard")
        compose.onNodeWithTag("book-cache-setting-cancel").performClick(); assertEquals("GB18030", value.preferences.charset); assertEquals(1, writes)
    }
    @Test fun darkSmallViewportCanScrollCustomSectionToScopeAndConfirmWithoutLosingDraft() {
        var value by mutableStateOf(state().copy(section = BookCacheSection("ticket", "path", name = "name", invalidScope = true)))
        var confirms = 0; var previews = 0
        val actions = BookCacheActions(section = { all, size, scope, name -> value = value.copy(section = value.section!!.let { it.copy(all = all ?: it.all, size = size ?: it.size, scope = scope ?: it.scope, name = name ?: it.name) }) }, confirmSection = { confirms++ }, preview = { previews++ })
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.size(320.dp, 420.dp)) { BookCacheScreen(value, actions) } } }
        compose.onNodeWithTag("book-cache-episode-scope").performScrollTo().performTextReplacement("1-5,8")
        compose.onNodeWithTag("book-cache-episode-name").performScrollTo().performTextReplacement("author")
        compose.onNodeWithTag("book-cache-episode-preview").performClick(); compose.onNodeWithTag("book-cache-confirm-section").performClick()
        assertEquals("1-5,8", value.section!!.scope); assertEquals("author", value.section!!.name); assertEquals(1, confirms); assertEquals(1, previews)
    }
}
