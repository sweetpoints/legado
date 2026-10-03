package io.legado.app.ui.book.read.config

import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.PageKeySettingsRepository
import io.legado.app.data.preferences.PageKeyValues
import org.junit.Assert.*
import org.junit.Test

class PageKeyViewModelTest {
    @Test
    fun loadingAndTextEditingDoNotPersistUntilConfirmation() {
        val repo = FakeRepository(PageKeyValues("19", "20"))
        val model = PageKeyViewModel(repo, SavedStateHandle())
        assertEquals(repo.stored, model.state.value.values)
        model.edit(PageKeyField.PREVIOUS, " 19,, 21 ")
        model.edit(PageKeyField.NEXT, "")
        assertTrue(repo.saved.isEmpty())
        model.confirm()
        assertEquals(listOf(PageKeyValues(" 19,, 21 ", "")), repo.saved)
        assertTrue(model.state.value.finished)
    }

    @Test
    fun resetOnlyChangesDraftAndConfirmCanPersistBothEmptyStrings() {
        val repo = FakeRepository(PageKeyValues("19", "20"))
        val model = PageKeyViewModel(repo, SavedStateHandle())
        model.reset()
        assertEquals(PageKeyValues(), model.state.value.values)
        assertTrue(repo.saved.isEmpty())
        model.confirm()
        assertEquals(listOf(PageKeyValues()), repo.saved)
    }

    @Test
    fun hardwareCodesAppendToFocusedFieldAndRespectExistingTrailingComma() {
        val repo = FakeRepository(PageKeyValues("19,", ""))
        val model = PageKeyViewModel(repo, SavedStateHandle())
        model.focus(PageKeyField.PREVIOUS, true)
        assertTrue(model.keyDown(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(model.keyDown(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals("19,24,24", model.state.value.values.previous)
        model.focus(PageKeyField.NEXT, true)
        assertTrue(model.keyDown(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals("25", model.state.value.values.next)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun backDeleteAndMissingFocusRemainUnhandled() {
        val model = PageKeyViewModel(FakeRepository(), SavedStateHandle())
        assertFalse(model.keyDown(KeyEvent.KEYCODE_VOLUME_UP))
        model.focus(PageKeyField.PREVIOUS, true)
        assertFalse(model.keyDown(KeyEvent.KEYCODE_BACK))
        assertFalse(model.keyDown(KeyEvent.KEYCODE_DEL))
        assertEquals(PageKeyValues(), model.state.value.values)
    }

    @Test
    fun losingPreviousFocusDoesNotClearAlreadyFocusedNextField() {
        val model = PageKeyViewModel(FakeRepository(), SavedStateHandle())
        model.focus(PageKeyField.PREVIOUS, true)
        model.focus(PageKeyField.NEXT, true)
        model.focus(PageKeyField.PREVIOUS, false)
        assertEquals(PageKeyField.NEXT, model.state.value.focused)
        model.focus(PageKeyField.NEXT, false)
        assertNull(model.state.value.focused)
    }

    @Test
    fun restoredDraftAndFocusAvoidReloadAndNeverWritePreferences() {
        val repo = FakeRepository(PageKeyValues("19", "20"))
        val handle = SavedStateHandle()
        val model = PageKeyViewModel(repo, handle)
        model.edit(PageKeyField.PREVIOUS, "19,")
        model.edit(PageKeyField.NEXT, "25,26")
        model.focus(PageKeyField.NEXT, true)
        val restored =
            PageKeyViewModel(
                repo,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertEquals(model.state.value.values, restored.state.value.values)
        assertEquals(PageKeyField.NEXT, restored.state.value.focused)
        assertEquals(1, repo.loads)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun repeatedConfirmationAndChangesAfterConfirmationCannotWriteAgain() {
        val repo = FakeRepository()
        val model = PageKeyViewModel(repo, SavedStateHandle())
        model.edit(PageKeyField.PREVIOUS, "19")
        model.focus(PageKeyField.PREVIOUS, true)
        model.confirm()
        model.confirm()
        model.edit(PageKeyField.NEXT, "20")
        model.reset()
        assertFalse(model.keyDown(25))
        assertEquals(listOf(PageKeyValues("19", "")), repo.saved)
        assertEquals(PageKeyValues("19", ""), model.state.value.values)
    }

    @Test
    fun failedConfirmationKeepsDraftAndCanRetry() {
        val repo = FakeRepository().apply { failSave = true }
        val model = PageKeyViewModel(repo, SavedStateHandle())
        model.edit(PageKeyField.NEXT, "20")
        model.confirm()
        assertEquals("save failed", model.state.value.error)
        assertFalse(model.state.value.finished)
        repo.failSave = false
        model.confirm()
        assertNull(model.state.value.error)
        assertTrue(model.state.value.finished)
        assertEquals(listOf(PageKeyValues("", "20")), repo.saved)
    }

    private class FakeRepository(val stored: PageKeyValues = PageKeyValues()) :
        PageKeySettingsRepository {
        var loads = 0
        var failSave = false
        val saved = mutableListOf<PageKeyValues>()

        override fun load(): PageKeyValues {
            loads++
            return stored
        }

        override fun save(values: PageKeyValues) {
            if (failSave) error("save failed")
            saved += values
        }
    }
}
