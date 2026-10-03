package io.legado.app.ui.group

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class NamedGroupComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rowActionsKeepTheCapturedGroupAcrossReordering() {
        val rows = mutableStateOf(listOf("One", "Two"))
        val edits = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                NamedGroupScreen(
                    NamedGroupState(groups = rows.value, loading = false),
                    {},
                    edits::add,
                    deletes::add,
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        compose.runOnIdle { rows.value = listOf("New", "Two", "One") }
        compose.onNodeWithTag("named-group-edit-Two").performClick()
        compose.onNodeWithTag("named-group-delete-One").performClick()
        assertEquals(listOf("Two"), edits)
        assertEquals(listOf("One"), deletes)
    }

    @Test
    fun nameEntryConfirmationAndCancellationUseSeparateActions() {
        val text = mutableStateOf("One")
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            LegadoComposeTheme {
                NamedGroupScreen(
                    NamedGroupState(
                        loading = false,
                        editing = true,
                        original = "One",
                        name = text.value,
                    ),
                    {},
                    {},
                    {},
                    { text.value = it },
                    { confirmed++ },
                    { cancelled++ },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("named-group-name").performTextReplacement(" New ")
        compose.onNodeWithTag("named-group-name").assertTextEquals(" New ")
        compose.onNodeWithTag("named-group-confirm").performClick()
        compose.onNodeWithTag("named-group-cancel").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, cancelled)
    }

    @Test
    fun narrowDarkDialogShowsLastRowAndDoneWhileBusyBlocksChanges() {
        val busy = mutableStateOf(false)
        var done = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(320.dp).height(500.dp)) {
                    NamedGroupScreen(
                        NamedGroupState(
                            groups = (1..30).map { "Group $it" },
                            loading = false,
                            busy = busy.value,
                        ),
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        { done++ },
                        showDone = true,
                    )
                }
            }
        }
        compose.onNodeWithTag("named-group-list").performScrollToNode(hasText("Group 30"))
        compose.onNodeWithTag("named-group-edit-Group 30").assertIsDisplayed()
        compose.onNodeWithTag("named-group-done").performClick()
        assertEquals(1, done)
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("named-group-add").assertIsNotEnabled()
        compose.onNodeWithTag("named-group-edit-Group 30").assertIsNotEnabled()
        compose.onNodeWithTag("named-group-done").assertIsNotEnabled()
    }
}
