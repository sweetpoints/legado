package io.legado.app.ui.main.bookshelf.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.data.preferences.BookshelfSettingsDraft
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookshelfSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun allSwitchesSelectorsAndGridTitleRemainAvailable() {
        var state by mutableStateOf(BookshelfSettingsDraft()); var confirms = 0; var cancels = 0
        compose.setContent { LegadoComposeTheme {
            BookshelfSettingsScreen(state, { state = it.normalized() }, { confirms++ }, { cancels++ }, Modifier.fillMaxWidth().heightIn(max = 560.dp))
        } }
        compose.onNodeWithTag("shelf-settings-title").assertDoesNotExist()
        listOf("unread", "latest", "wait", "fast", "recent", "stats").forEach { tag ->
            compose.onNodeWithTag("shelf-settings-$tag").performScrollTo().performClick()
        }
        listOf("group" to 1, "progress" to 2, "layout" to 6, "sort" to 5, "title" to 2).forEach { (tag, index) ->
            compose.onNodeWithTag("shelf-settings-$tag").performScrollTo().performClick()
            compose.onNodeWithTag("shelf-settings-$tag-$index").performScrollTo().performClick()
        }
        compose.onNodeWithTag("shelf-settings-confirm").performClick()
        compose.onNodeWithTag("shelf-settings-cancel").performClick()
        compose.runOnIdle {
            assertEquals(BookshelfSettingsDraft(groupStyle = 1, progress = 2, unread = false, latest = true,
                waitCount = true, fastScroll = true, recent = true, stats = true, layout = 6, sort = 5, title = 2), state)
            assertEquals(1, confirms); assertEquals(1, cancels)
        }
    }
    @Test fun marginRetainsOneStepControlsAndEndpointDisabling() {
        var state by mutableStateOf(BookshelfSettingsDraft(margin = 0))
        compose.setContent { LegadoComposeTheme {
            BookshelfSettingsScreen(state, { state = it.normalized() }, {}, {}, Modifier.fillMaxWidth().heightIn(max = 520.dp))
        } }
        compose.onNodeWithTag("shelf-settings-margin-minus").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("shelf-settings-margin-plus").performClick()
        compose.runOnIdle { assertEquals(1, state.margin); state = state.copy(margin = 60) }
        compose.onNodeWithTag("shelf-settings-margin-plus").assertIsNotEnabled()
        compose.onNodeWithTag("shelf-settings-margin-minus").performClick()
        compose.runOnIdle { assertEquals(59, state.margin) }
    }
}
