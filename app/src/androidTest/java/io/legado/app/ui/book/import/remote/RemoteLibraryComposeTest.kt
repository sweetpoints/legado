package io.legado.app.ui.book.import.remote

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.model.remote.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class RemoteLibraryComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun initial() =
        RemoteLibraryState(
            loading = false,
            connection = RemoteLibraryConnection("connection", "root", true, null),
            draft =
                RemoteLibraryDraft(
                    rows =
                        listOf(
                            RemoteLibraryEntry("folder", "Folder", "folder", 0, 0, "folder", false),
                            RemoteLibraryEntry("a", "Alpha", "a", 123, 1, "txt", false),
                            RemoteLibraryEntry(
                                "existing",
                                "Existing",
                                "existing",
                                456,
                                2,
                                "zip",
                                true,
                            ),
                        )
                ),
        )

    private fun actions() =
        RemoteLibraryActions(
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
        )

    private fun render(
        state: RemoteLibraryState = initial(),
        actions: RemoteLibraryActions = actions(),
    ) {
        compose.setContent { LegadoComposeTheme { RemoteLibraryScreen(state, actions) } }
    }

    @Test
    fun directoryNewBookAndExistingBookRowsDispatchIndependentActionsAndLongPressReimportsOnlyExisting() {
        val directories = mutableListOf<String>()
        val toggles = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val reimports = mutableListOf<String>()
        render(
            actions =
                actions()
                    .copy(
                        openDirectory = { directories += it },
                        toggle = { toggles += it },
                        read = { reads += it },
                        reimport = { reimports += it },
                    )
        )
        compose
            .onNodeWithTag("remote-library-row-folder")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("remote-library-row-a").performClick()
        compose.onNodeWithTag("remote-library-selected-a").performClick()
        compose.onNodeWithTag("remote-library-row-existing").performClick()
        compose.onNodeWithTag("remote-library-row-existing").performTouchInput { longClick() }
        compose.onNodeWithTag("remote-library-row-folder").performTouchInput { longClick() }
        assertEquals(listOf("folder"), directories)
        assertEquals(listOf("a", "a"), toggles)
        assertEquals(listOf("existing"), reads)
        assertEquals(listOf("existing"), reimports)
        compose.onNodeWithTag("remote-library-selected-folder").assertDoesNotExist()
        compose.onNodeWithTag("remote-library-selected-existing").assertDoesNotExist()
    }

    @Test
    fun refreshSortAndAllThreeOriginalMenuActionsRemainAccessible() {
        var refreshed = 0
        val sorts = mutableListOf<RemoteLibrarySort>()
        val menus = mutableListOf<RemoteLibraryEffect>()
        render(
            actions =
                actions()
                    .copy(refresh = { refreshed++ }, sort = { sorts += it }, menu = { menus += it })
        )
        compose.onNodeWithTag("remote-library-refresh").performClick()
        assertEquals(1, refreshed)
        RemoteLibrarySort.entries.forEach { sort ->
            compose.onNodeWithTag("remote-library-sort").performClick()
            compose.onNodeWithTag("remote-library-sort-${sort.name}").performClick()
        }
        assertEquals(RemoteLibrarySort.entries, sorts)
        listOf(RemoteLibraryEffect.Servers, RemoteLibraryEffect.Help, RemoteLibraryEffect.Log)
            .forEach { effect ->
                compose.onNodeWithTag("remote-library-menu").performClick()
                compose.onNodeWithTag("remote-library-menu-${effect.name}").performClick()
            }
        assertEquals(
            listOf(RemoteLibraryEffect.Servers, RemoteLibraryEffect.Help, RemoteLibraryEffect.Log),
            menus,
        )
    }

    @Test
    fun hiddenSelectionStillCountsAndImportsWhileVisibleAllAndInverseAreSeparate() {
        var imported = 0
        var inversed = 0
        var all: Boolean? = null
        val state =
            initial()
                .copy(draft = initial().draft!!.copy(query = "Existing", selected = listOf("a")))
        render(
            state,
            actions().copy(import = { imported++ }, inverse = { inversed++ }, all = { all = it }),
        )
        compose.onNodeWithTag("remote-library-count").assertTextEquals("1 / 0")
        compose.onNodeWithTag("remote-library-import").assertIsEnabled().performClick()
        assertEquals(1, imported)
        compose.onNodeWithTag("remote-library-inverse").performClick()
        assertEquals(1, inversed)
        compose.onNodeWithTag("remote-library-all").performClick()
        assertEquals(true, all)
    }

    @Test
    fun archiveSelectorDoesNotImportUntilUserChoosesThenConfirmsAndStorageCancelHasOwnCallback() {
        var state by
            mutableStateOf(
                initial()
                    .copy(
                        draft =
                            initial()
                                .draft!!
                                .copy(
                                    confirmation =
                                        RemoteLibraryConfirmation(
                                            RemoteLibraryPrompt.ChooseArchive,
                                            uri = "content://archive",
                                            names = listOf("a.txt", "b.txt"),
                                        )
                                )
                    )
            )
        var chosen = ""
        var confirmed = 0
        var storageCanceled = 0
        compose.setContent {
            LegadoComposeTheme {
                RemoteLibraryScreen(
                    state,
                    actions()
                        .copy(
                            archive = { chosen = it },
                            confirm = { confirmed++ },
                            cancelStorage = { storageCanceled++ },
                        ),
                )
            }
        }
        compose.onNodeWithTag("remote-library-confirm").assertDoesNotExist()
        compose.onNodeWithTag("remote-library-archive-b.txt").performClick()
        assertEquals("b.txt", chosen)
        assertEquals(0, confirmed)
        compose.runOnIdle {
            state =
                state.copy(
                    draft =
                        state.draft!!.copy(
                            confirmation =
                                RemoteLibraryConfirmation(
                                    RemoteLibraryPrompt.ImportArchive,
                                    uri = "content://archive",
                                    name = "b.txt",
                                )
                        )
                )
        }
        compose.onNodeWithTag("remote-library-confirm").performClick()
        assertEquals(1, confirmed)
        compose.runOnIdle {
            state =
                state.copy(
                    draft =
                        state.draft!!.copy(
                            confirmation =
                                RemoteLibraryConfirmation(
                                    RemoteLibraryPrompt.StorageHelp,
                                    help = "Synthetic help",
                                )
                        )
                )
        }
        compose.onNodeWithText("Synthetic help").assertExists()
        compose.onNodeWithTag("remote-library-prompt-cancel").performClick()
        assertEquals(1, storageCanceled)
    }

    @Test
    fun failedConfigurationKeepsServersHelpAndLogMenuEnabledAndBusyAcceptedCommitBlocksImport() {
        var state by mutableStateOf(initial().copy(failed = true, error = "configuration failed"))
        val menus = mutableListOf<RemoteLibraryEffect>()
        compose.setContent {
            LegadoComposeTheme {
                RemoteLibraryScreen(state, actions().copy(menu = { menus += it }))
            }
        }
        compose.onNodeWithTag("remote-library-menu").assertIsEnabled().performClick()
        compose.onNodeWithTag("remote-library-menu-Servers").performClick()
        assertEquals(listOf(RemoteLibraryEffect.Servers), menus)
        compose.runOnIdle { state = state.copy(failed = false, busy = true, error = null) }
        compose.onNodeWithTag("remote-library-import").assertIsNotEnabled()
    }

    @Test
    fun pathBackAndInterruptedTaskExplicitRetryAndDiscardAreSeparateFromPageClose() {
        var state by
            mutableStateOf(
                initial()
                    .copy(
                        draft =
                            initial()
                                .draft!!
                                .copy(directories = listOf(initial().draft!!.rows.first()))
                    )
            )
        var back = 0
        var retry = 0
        var discard = 0
        compose.setContent {
            LegadoComposeTheme {
                RemoteLibraryScreen(
                    state,
                    actions()
                        .copy(
                            directoryBack = { back++ },
                            retryTask = { retry++ },
                            discardTask = { discard++ },
                        ),
                )
            }
        }
        compose.onNodeWithTag("remote-library-path").assertTextEquals("books/Folder/")
        compose.onNodeWithTag("remote-library-directory-back").performClick()
        assertEquals(1, back)
        compose.runOnIdle { state = state.copy(interrupted = true) }
        compose.onNodeWithTag("remote-library-retry-task").performClick()
        assertEquals(1, retry)
        compose.onNodeWithTag("remote-library-discard-task").performClick()
        assertEquals(1, discard)
    }
}
