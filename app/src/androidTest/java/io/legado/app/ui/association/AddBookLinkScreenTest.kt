package io.legado.app.ui.association

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AddBookLinkScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun loadingRemainsCancellableAndShowsProgressInShortWindow() {
        var cancels = 0
        compose.setContent { LegadoComposeTheme { AddBookLinkScreen(AddBookLinkState(), { cancels++ }, Modifier.height(180.dp)) } }
        compose.onNodeWithTag("add-book-link-progress").assertExists()
        compose.onNodeWithTag("add-book-link-cancel").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }
    @Test fun completedStateRemovesProgressAndDisablesFurtherCancel() {
        compose.setContent { LegadoComposeTheme { AddBookLinkScreen(AddBookLinkState(loading = false, finished = true), {}) } }
        compose.onNodeWithTag("add-book-link-progress").assertDoesNotExist()
        compose.onNodeWithTag("add-book-link-cancel").assertIsNotEnabled()
    }
}
