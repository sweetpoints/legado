package io.legado.app.ui.widget.dialog

import androidx.lifecycle.SavedStateHandle
import io.legado.app.ui.widget.dialog.variable.VariableResult
import io.legado.app.ui.widget.dialog.variable.VariableViewModel
import org.junit.Assert.*
import org.junit.Test

/** Replaces XML assertions with editable draft and delivery behavior. */
class VariableDialogLayoutTest {
    @Test fun constructorParametersInitializeAnImmediateDraft() {
        val model = VariableViewModel(SavedStateHandle(mapOf("title" to "title", "key" to "key", "variable" to "before", "comment" to "help")))
        assertEquals("title", model.state.value.title)
        assertEquals("key", model.state.value.key)
        assertEquals("before", model.state.value.input)
        assertEquals("help", model.state.value.comment)
    }

    @Test fun nullInputDisplaysAndSavesEmptyTextLikeThePreviousEditText() {
        val model = VariableViewModel(SavedStateHandle(mapOf("key" to "key", "variable" to null)))
        assertEquals("", model.state.value.input)
        model.requestSave()
        assertEquals(VariableResult("key", ""), model.consumeSave())
    }

    @Test fun multilineDraftSurvivesRecreationWithoutNormalizingWhitespace() {
        val handle = SavedStateHandle(mapOf("variable" to "before", "key" to "source"))
        val model = VariableViewModel(handle)
        model.setInput("  first\nsecond\n ")
        val restored = VariableViewModel(copy(handle))
        assertEquals("  first\nsecond\n ", restored.state.value.input)
        assertNull(restored.consumeSave())
        restored.requestSave()
        assertEquals(VariableResult("source", "  first\nsecond\n "), restored.consumeSave())
    }

    @Test fun clearingTheDraftDoesNotRestoreTheOriginalText() {
        val handle = SavedStateHandle(mapOf("variable" to "before"))
        VariableViewModel(handle).setInput("")
        val restored = VariableViewModel(copy(handle))
        assertEquals("", restored.state.value.input)
    }

    @Test fun onlySaveProducesAResultAndItCannotBeDeliveredTwice() {
        val model = VariableViewModel(SavedStateHandle(mapOf("key" to "key")))
        model.setInput("typed")
        assertNull(model.consumeSave())
        model.requestSave()
        model.requestSave()
        model.setInput("late edit")
        assertEquals(VariableResult("key", "typed"), model.consumeSave())
        assertNull(model.consumeSave())
        model.requestSave()
        assertNull(model.consumeSave())
    }

    @Test fun cancelledDraftNeverRequestsSaveIncludingAcrossRecreation() {
        val handle = SavedStateHandle()
        val model = VariableViewModel(handle)
        model.setInput("unsaved")
        model.cancel()
        model.requestSave()
        assertNull(model.consumeSave())
        val restored = VariableViewModel(copy(handle))
        assertTrue(restored.state.value.finished)
        assertNull(restored.consumeSave())
    }

    @Test fun restoredConsumedSaveStaysFinishedWithoutAnotherResult() {
        val handle = SavedStateHandle(mapOf("key" to "source", "variable" to "value"))
        val original = VariableViewModel(handle)
        original.requestSave()
        original.consumeSave()
        val restored = VariableViewModel(copy(handle))
        assertTrue(restored.state.value.finished)
        assertFalse(restored.state.value.saveRequested)
        assertNull(restored.consumeSave())
    }

    @Test fun pendingSaveIsRestoredAndCancellationCanDiscardIt() {
        val handle = SavedStateHandle(mapOf("key" to "key", "variable" to "value"))
        val model = VariableViewModel(handle)
        model.requestSave()
        val restored = VariableViewModel(copy(handle))
        assertEquals(VariableResult("key", "value"), restored.consumeSave())
        val cancelled = VariableViewModel(copy(handle))
        cancelled.cancel()
        assertNull(cancelled.consumeSave())
    }

    private fun copy(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
}
