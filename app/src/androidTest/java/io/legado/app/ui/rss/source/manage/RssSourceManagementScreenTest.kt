package io.legado.app.ui.rss.source.manage

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.RssSourceManagementRow
import io.legado.app.data.repository.RssSourceManagementShareFeedback
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class RssSourceManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rows =
        (0..99).map { RssSourceManagementRow("id-$it", "Source $it", "Source $it", "A", true, it) }

    private fun actions(
        selected: (String, Boolean) -> Unit = { _, _ -> },
        invert: () -> Unit = {},
        interval: () -> Unit = {},
        edge: (List<String>, Boolean) -> Unit = { _, _ -> },
        dialog: (RssSourceManagementDialog, List<String>) -> Unit = { _, _ -> },
        effect: (RssSourceManagementAction, String?) -> Unit = { _, _ -> },
        copy: () -> Unit = {},
        passphrase: () -> Unit = {},
        scroll: (Int, Int) -> Unit = { _, _ -> },
    ) =
        RssSourceManagementActions(
            {},
            { _, _, _ -> },
            selected,
            {},
            invert,
            interval,
            { _, _ -> },
            edge,
            dialog,
            { _, _, _ -> },
            {},
            {},
            {},
            effect,
            {},
            passphrase,
            copy,
            {},
            { true },
            { _, _ -> },
            {},
            { true },
            { _, _ -> },
            {},
            {},
            scroll,
        )

    private fun show(
        state: RssSourceManagementState,
        actions: RssSourceManagementActions = actions(),
    ) {
        compose.setContent { LegadoComposeTheme { RssSourceManagementScreen(state, actions) } }
    }

    @Test
    fun selectionLabelAndAllSelectedButtonUseOriginalToggleContracts() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var inversions = 0
        show(
            RssSourceManagementState(
                loaded = true,
                rows = rows.take(2),
                selected = setOf("id-0", "id-1"),
            ),
            actions(
                selected = { id, checked -> calls += id to checked },
                invert = { inversions++ },
            ),
        )
        compose.onNodeWithText("Source 0").performClick()
        compose.onNodeWithTag("rss-source-all").performClick()
        assertEquals(listOf("id-0" to false), calls)
        assertEquals(1, inversions)
        compose.onNodeWithTag("rss-source-count").assertTextEquals("2/2")
    }

    @Test
    fun rowMenuKeepsTopBottomAndDeleteTargetAndOrder() {
        val edges = mutableListOf<Pair<List<String>, Boolean>>()
        val dialogs = mutableListOf<Pair<RssSourceManagementDialog, List<String>>>()
        show(
            RssSourceManagementState(loaded = true, rows = rows.take(1)),
            actions(
                edge = { ids, top -> edges += ids to top },
                dialog = { kind, ids -> dialogs += kind to ids },
            ),
        )
        fun open() = compose.onNodeWithTag("rss-source-menu-id-0").performClick()
        open()
        val top = compose.onNodeWithTag("rss-source-top-id-0").fetchSemanticsNode().boundsInRoot.top
        val bottom =
            compose.onNodeWithTag("rss-source-bottom-id-0").fetchSemanticsNode().boundsInRoot.top
        val delete =
            compose.onNodeWithTag("rss-source-delete-id-0").fetchSemanticsNode().boundsInRoot.top
        assertTrue(top < bottom && bottom < delete)
        compose.onNodeWithTag("rss-source-top-id-0").performClick()
        open()
        compose.onNodeWithTag("rss-source-bottom-id-0").performClick()
        open()
        compose.onNodeWithTag("rss-source-delete-id-0").performClick()
        assertEquals(listOf(listOf("id-0") to true, listOf("id-0") to false), edges)
        assertEquals(listOf(RssSourceManagementDialog.Delete to listOf("id-0")), dialogs)
    }

    @Test
    fun intervalAndExportActionsTargetVisibleSelection() {
        var intervals = 0
        val effects = mutableListOf<RssSourceManagementAction>()
        show(
            RssSourceManagementState(
                loaded = true,
                rows = rows.take(3),
                selected = setOf("id-0", "hidden"),
            ),
            actions(interval = { intervals++ }, effect = { action, _ -> effects += action }),
        )
        compose.onNodeWithTag("rss-source-count").assertTextEquals("1/3")
        compose.onNodeWithTag("rss-source-selection-menu").performClick()
        compose.onNodeWithTag("rss-source-interval").performScrollTo().performClick()
        compose.onNodeWithTag("rss-source-selection-menu").performClick()
        compose.onNodeWithTag("rss-source-export").performClick()
        assertEquals(1, intervals)
        assertEquals(listOf(RssSourceManagementAction.Export), effects)
    }

    @Test
    fun originalAddImportQrAndHelpEntriesStayAvailable() {
        val effects = mutableListOf<RssSourceManagementAction>()
        show(
            RssSourceManagementState(loaded = true),
            actions(effect = { action, _ -> effects += action }),
        )
        for (tag in listOf("rss-source-add", "rss-source-import-local", "rss-source-import-qr")) {
            compose.onNodeWithTag("rss-source-more").performClick()
            compose.onNodeWithTag(tag).performClick()
        }
        compose.onNodeWithTag("rss-source-more").performClick()
        compose.onNodeWithText(context.getString(R.string.help)).performClick()
        assertEquals(
            listOf(
                RssSourceManagementAction.Add,
                RssSourceManagementAction.ImportLocal,
                RssSourceManagementAction.ImportQr,
                RssSourceManagementAction.Help,
            ),
            effects,
        )
    }

    @Test
    fun loadingListDoesNotOverwriteSavedScrollAndRestoresAfterRowsArrive() {
        var state by mutableStateOf(RssSourceManagementState(scrollIndex = 40, scrollOffset = 9))
        val observed = mutableListOf<Int>()
        compose.setContent {
            LegadoComposeTheme {
                RssSourceManagementScreen(
                    state,
                    actions(scroll = { index, _ -> observed += index }),
                )
            }
        }
        compose.waitForIdle()
        assertTrue(observed.isEmpty())
        compose.runOnIdle { state = state.copy(loaded = true, rows = rows) }
        compose.onNodeWithTag("rss-source-row-id-40").assertIsDisplayed()
        assertFalse(observed.contains(0))
    }

    @Test
    fun accessibleFastScrollMakesTargetAtEndVisible() {
        show(RssSourceManagementState(loaded = true, rows = rows))
        compose.onNodeWithTag("rss-source-fast-scroll").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            assertTrue(it(1f))
        }
        compose.onNodeWithTag("rss-source-row-id-99").assertIsDisplayed()
    }

    @Test
    fun exportResultShowsSummaryAndOffersOriginalCopyAndPassphraseActions() {
        var copies = 0
        var phrases = 0
        show(
            RssSourceManagementState(
                loaded = true,
                dialog = RssSourceManagementDialog.ExportResult,
                feedback =
                    RssSourceManagementShareFeedback(
                        "https://example.invalid",
                        "Expiry metadata",
                        true,
                    ),
                draft = "https://example.invalid",
            ),
            actions(copy = { copies++ }, passphrase = { phrases++ }),
        )
        compose.onNodeWithText("Expiry metadata").assertIsDisplayed()
        compose.onNodeWithTag("rss-source-passphrase").performClick()
        compose.onNodeWithTag("rss-source-dialog-confirm").performClick()
        assertEquals(1, copies)
        assertEquals(1, phrases)
    }
}
