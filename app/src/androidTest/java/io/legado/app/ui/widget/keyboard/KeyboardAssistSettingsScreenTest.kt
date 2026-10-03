package io.legado.app.ui.widget.keyboard

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

class KeyboardAssistSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val initial =
        listOf(
            KeyboardAssistSettingsRow("a", 0, "A", "a", 1),
            KeyboardAssistSettingsRow("b", 1, "B", "b", 2),
            KeyboardAssistSettingsRow("c", 0, "C", "c", 3),
        )
    private val state = mutableStateOf(KeyboardAssistSettingsState(rows = initial, loaded = true))

    private fun show(actions: KeyboardAssistSettingsActions = KeyboardAssistSettingsActions()) {
        compose.setContent {
            LegadoComposeTheme { KeyboardAssistSettingsScreen(state.value, actions) }
        }
    }

    @Test
    fun addEditAndImmediateDeleteKeepStableIdsAndDisplayAllTypes() {
        var adds = 0
        val edits = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        show(
            KeyboardAssistSettingsActions(
                add = { adds++ },
                edit = { edits += it },
                delete = { deletes += it },
            )
        )
        compose.onNodeWithTag("keyboard-settings-add").performClick()
        compose.onNodeWithTag("keyboard-settings-row-b").performClick()
        compose.onNodeWithTag("keyboard-settings-delete-a").performClick()
        assertEquals(1, adds)
        assertEquals(listOf("b"), edits)
        assertEquals(listOf("a"), deletes)
    }

    @Test
    fun handleReleaseConsumesClickAndCancellationNeverCommits() {
        var commits = 0
        var cancels = 0
        var edits = 0
        show(
            KeyboardAssistSettingsActions(
                begin = {
                    state.value = state.value.copy(dragging = true)
                    true
                },
                move = { id, target ->
                    val rows = state.value.rows.toMutableList()
                    val from = rows.indexOfFirst { it.id == id }
                    val to = rows.indexOfFirst { it.id == target }
                    rows.add(to, rows.removeAt(from))
                    state.value = state.value.copy(rows = rows)
                },
                finish = {
                    commits++
                    state.value = state.value.copy(dragging = false)
                },
                cancelDrag = {
                    cancels++
                    state.value = state.value.copy(rows = initial, dragging = false)
                },
                edit = { edits++ },
            )
        )
        val list = compose.onNodeWithTag("keyboard-settings-list").fetchSemanticsNode().boundsInRoot
        val a = compose.onNodeWithTag("keyboard-settings-drag-a").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("keyboard-settings-drag-c").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("keyboard-settings-list").performTouchInput {
            down(Offset(a.center.x - list.left, a.center.y - list.top))
            moveTo(Offset(c.center.x - list.left, c.center.y - list.top))
            up()
        }
        assertEquals(1, commits)
        assertEquals(0, edits)
        compose.onNodeWithTag("keyboard-settings-list").performTouchInput {
            down(Offset(10f, 10f))
            moveTo(Offset(10f, 80f))
            cancel()
        }
        assertEquals(1, cancels)
        assertEquals(1, commits)
    }

    @Test
    fun accessibilityReorderActionsMatchAvailableBounds() {
        val moves = mutableListOf<Pair<String, Int>>()
        show(KeyboardAssistSettingsActions(step = { id, direction -> moves += id to direction }))
        val first =
            compose
                .onNodeWithTag("keyboard-settings-row-a")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        val middle =
            compose
                .onNodeWithTag("keyboard-settings-row-b")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        assertEquals(1, first.size)
        assertEquals(2, middle.size)
        compose.runOnIdle {
            first.single().action()
            middle.first().action()
        }
        assertEquals(listOf("a" to 1, "b" to -1), moves)
    }

    @Test
    fun ordinarySaveFailureAllowsCorrectionSelectionAndRetryOrCancel() {
        state.value =
            state.value.copy(
                editor = KeyboardAssistSettingsDraft(initial[0]),
                error = "save failed",
            )
        var saves = 0
        var cancels = 0
        show(
            KeyboardAssistSettingsActions(
                text = { key, value ->
                    val draft = state.value.editor!!
                    state.value =
                        state.value.copy(
                            editor = if (key) draft.copy(key = value) else draft.copy(value = value)
                        )
                },
                saveEditor = { saves++ },
                cancelEditor = { cancels++ },
            )
        )
        compose.onNodeWithTag("keyboard-settings-editor-key").performTextReplacement(" Key ")
        compose.onNodeWithTag("keyboard-settings-editor-value").performTextReplacement(" Value ")
        compose
            .onNodeWithTag("keyboard-settings-editor-value")
            .performTextInputSelection(TextRange(4, 1))
        compose.onNodeWithTag("keyboard-settings-editor-save").performClick()
        compose.onNodeWithTag("keyboard-settings-editor-cancel").performClick()
        assertEquals(" Key ", state.value.editor!!.key.text)
        assertEquals(4, state.value.editor!!.value.start)
        assertEquals(1, state.value.editor!!.value.end)
        assertEquals(1, saves)
        assertEquals(1, cancels)
    }

    @Test
    fun unsafeValuesOpenFullEditorAndPreserveRawText() {
        val raw = "x".repeat(20000)
        state.value =
            state.value.copy(
                editor = KeyboardAssistSettingsDraft(value = KeyboardAssistSettingsText(raw))
            )
        val codes = mutableListOf<Boolean>()
        show(KeyboardAssistSettingsActions(code = { codes += it }))
        compose.onNodeWithTag("keyboard-settings-editor-value").performClick()
        assertEquals(listOf(false), codes)
        assertEquals(raw, state.value.editor!!.value.text)
    }

    @Test
    fun linePickerOffersOneThroughFiveAndSeparatesCancelFromConfirm() {
        state.value = state.value.copy(linePicker = true, selectedLines = 2)
        val chosen = mutableListOf<Int>()
        var confirmed = 0
        var canceled = 0
        show(
            KeyboardAssistSettingsActions(
                chooseLines = {
                    chosen += it
                    state.value = state.value.copy(selectedLines = it)
                },
                saveLines = { confirmed++ },
                cancelLines = { canceled++ },
            )
        )
        for (rows in 1..5) compose.onNodeWithTag("keyboard-settings-lines-$rows").assertExists()
        compose.onNodeWithTag("keyboard-settings-lines-6").assertDoesNotExist()
        compose.onNodeWithTag("keyboard-settings-lines-5").performClick().assertIsSelected()
        compose.onNodeWithTag("keyboard-settings-lines-cancel").performClick()
        compose.onNodeWithTag("keyboard-settings-lines-save").performClick()
        assertEquals(listOf(5), chosen)
        assertEquals(1, canceled)
        assertEquals(1, confirmed)
    }

    @Test
    fun preferenceEventWaitsForResumedAndIsConsumedBeforeCallbackExactlyOnce() {
        class Owner : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle
                get() = registry
        }
        val repo =
            object : KeyboardAssistSettingsRepository {
                override fun observe() = MutableStateFlow(initial)

                override suspend fun initialRows() = 2

                override suspend fun setRows(rows: Int) {}

                override suspend fun loadEditor(session: String, id: String?) =
                    KeyboardAssistSettingsDraft()

                override suspend fun writeEditor(
                    session: String,
                    draft: KeyboardAssistSettingsDraft,
                ) {}

                override suspend fun saveEditor(
                    session: String,
                    draft: KeyboardAssistSettingsDraft,
                ) = draft.copy(open = false)

                override suspend fun delete(id: String) {}

                override suspend fun reorder(ids: List<String>) {}
            }
        lateinit var model: KeyboardAssistSettingsViewModel
        lateinit var owner: Owner
        val events = mutableListOf<Int>()
        compose.runOnIdle {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = KeyboardAssistSettingsViewModel(repo, SavedStateHandle())
        }
        try {
            compose.setContent {
                LegadoComposeTheme {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        KeyboardAssistSettingsRoute(
                            model,
                            { true },
                            {},
                            {
                                events += it
                                assertNull(model.state.value.pendingLines)
                            },
                            {},
                            {},
                        )
                    }
                }
            }
            compose.waitUntil { model.state.value.loaded }
            compose.runOnIdle {
                model.openLinePicker()
                model.chooseLines(4)
                model.saveLines()
            }
            compose.waitUntil { model.state.value.pendingLines == 4 }
            assertTrue(events.isEmpty())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { events.size == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitForIdle()
            assertEquals(listOf(4), events)
        } finally {
            compose.runOnIdle { model.stop() }
        }
    }
}
