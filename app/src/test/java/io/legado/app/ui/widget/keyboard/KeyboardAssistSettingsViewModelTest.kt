package io.legado.app.ui.widget.keyboard

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardAssistSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<KeyboardAssistSettingsViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Fake : KeyboardAssistSettingsRepository {
        val rows = MutableStateFlow(listOf(KeyboardAssistSettingsRow("a", 0, "a", "A", 1), KeyboardAssistSettingsRow("b", 1, "b", "B", 2), KeyboardAssistSettingsRow("c", 0, "c", "C", 3)))
        val drafts = mutableMapOf<String, KeyboardAssistSettingsDraft>(); val sorts = mutableListOf<List<String>>(); val deletes = mutableListOf<String>(); val lineWrites = mutableListOf<Int>()
        var lines = 2; var gate: CompletableDeferred<Unit>? = null; var nonCooperative = false; var fail = false; var saves = 0
        override fun observe() = rows
        override suspend fun initialRows() = lines
        override suspend fun setRows(rows: Int) { lineWrites += rows; lines = rows }
        override suspend fun loadEditor(session: String, id: String?): KeyboardAssistSettingsDraft {
            if (nonCooperative) withContext(NonCancellable) { gate?.await() } else gate?.await()
            if (fail) error("failed"); return drafts[session] ?: KeyboardAssistSettingsDraft(rows.value.find { it.id == id }).also { drafts[session] = it }
        }
        override suspend fun writeEditor(session: String, draft: KeyboardAssistSettingsDraft) { if (draft.revision >= (drafts[session]?.revision ?: -1)) drafts[session] = draft }
        override suspend fun saveEditor(session: String, draft: KeyboardAssistSettingsDraft): KeyboardAssistSettingsDraft {
            saves++; gate?.await(); if (fail) error("failed")
            return draft.copy(open = false).also { drafts[session] = it }
        }
        override suspend fun delete(id: String) { deletes += id; rows.value = rows.value.filter { it.id != id } }
        override suspend fun reorder(ids: List<String>) {
            sorts += ids; check(ids.toSet() == rows.value.map { it.id }.toSet())
            rows.value = ids.mapIndexed { index, id -> rows.value.single { it.id == id }.copy(order = index + 1) }
        }
    }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = KeyboardAssistSettingsViewModel(repo, saved).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun cancelledDragRestoresLatestRoomRowsAndNeverWritesPartialOrder() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); assertTrue(model.beginDrag()); model.move(0, 2)
        assertEquals(listOf("b", "c", "a"), model.state.value.rows.map { it.id })
        repo.rows.value = repo.rows.value + KeyboardAssistSettingsRow("d", 0, "d", "D", 4); runCurrent()
        model.cancelDrag(); assertEquals(listOf("a", "b", "c", "d"), model.state.value.rows.map { it.id }); assertTrue(repo.sorts.isEmpty())
    }
    @Test fun finishedDragAndAccessibilityMovePersistExactOrderAndRepeatedFinishIsIgnored() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.beginDrag(); model.move(0, 2); model.finishDrag(); model.finishDrag(); runCurrent()
        assertEquals(listOf(listOf("b", "c", "a")), repo.sorts); assertEquals(listOf(1, 2, 3), model.state.value.rows.map { it.order })
        model.moveAccessibly("a", -1); runCurrent(); assertEquals(listOf("b", "a", "c"), repo.sorts.last())
        val count = repo.sorts.size; model.moveAccessibly("b", -1); runCurrent(); assertEquals(count, repo.sorts.size)
    }
    @Test fun processRestoreNeverResumesAnUnfinishedGestureButRestoresScroll() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent(); first.scroll(2, 17); first.beginDrag(); first.move(0, 2)
        val restored = model(repo, copy(saved)); runCurrent(); assertFalse(restored.state.value.dragging)
        assertEquals(listOf("a", "b", "c"), restored.state.value.rows.map { it.id }); assertEquals(2, restored.state.value.scroll); assertEquals(17, restored.state.value.offset)
    }
    @Test fun cancelledEditorKeepsRoomUntouchedAndRestoresLargeDraftWithOnlySmallSavedIds() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent(); first.openEditor("b"); runCurrent()
        val large = "value".repeat(40000); first.editorText(false, KeyboardAssistSettingsText(large, 29, 3)); runCurrent()
        val restored = model(repo, copy(saved)); runCurrent(); assertEquals(large, restored.state.value.editor!!.value.text); assertEquals(29, restored.state.value.editor!!.value.start)
        assertTrue(saved.keys().mapNotNull { saved.get<Any?>(it) }.filterIsInstance<String>().all { it.length < 100 })
        restored.cancelEditor(); runCurrent(); assertNull(restored.state.value.editor); assertEquals(0, repo.saves); assertEquals("B", repo.rows.value[1].value)
    }
    @Test fun saveBlocksDuplicateAndDeleteAndFailureAllowsRetry() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.openEditor(); runCurrent(); repo.gate = CompletableDeferred(); repo.fail = true
        model.editorText(true, KeyboardAssistSettingsText("")); model.saveEditor(); model.saveEditor(); model.delete("a"); model.cancelEditor(); runCurrent(); assertEquals(1, repo.saves); assertTrue(repo.deletes.isEmpty())
        repo.gate!!.complete(Unit); runCurrent(); assertNotNull(model.state.value.editor); assertFalse(model.state.value.busy)
        repo.fail = false; model.saveEditor(); runCurrent(); assertNull(model.state.value.editor); assertEquals(2, repo.saves)
    }
    @Test fun linePickerCancelWritesNothingAndConfirmedIntPayloadRestoresAndConsumesOnce() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent(); model.openLinePicker(); model.chooseLines(5); model.cancelLines(); assertTrue(repo.lineWrites.isEmpty())
        model.openLinePicker(); model.chooseLines(4); model.saveLines(); model.saveLines(); runCurrent(); assertEquals(listOf(4), repo.lineWrites)
        val restored = model(repo, copy(saved)); runCurrent(); assertEquals(4, restored.state.value.pendingLines); restored.linesDelivered(3); assertEquals(4, restored.state.value.pendingLines)
        restored.linesDelivered(4); restored.linesDelivered(4); assertNull(restored.state.value.pendingLines)
    }
    @Test fun nonCooperativeEditorLoadCannotPublishAfterStop() = test {
        val repo = Fake().apply { nonCooperative = true; gate = CompletableDeferred() }; val model = model(repo); runCurrent(); model.openEditor("a"); runCurrent(); model.stop(); repo.gate!!.complete(Unit); runCurrent(); assertNull(model.state.value.editor)
    }
    @Test fun immediateDeleteResolvesStableIdAndRoomUpdatesDoNotOverwriteEditorDraft() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.delete("a"); runCurrent(); assertEquals(listOf("a"), repo.deletes)
        model.openEditor("b"); runCurrent(); model.editorText(false, KeyboardAssistSettingsText("draft")); repo.rows.value = repo.rows.value.map { it.copy(value = "external") }; runCurrent()
        assertEquals("draft", model.state.value.editor!!.value.text); assertEquals("external", model.state.value.rows[0].value)
    }
}
