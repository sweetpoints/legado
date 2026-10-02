package io.legado.app.ui.main.bookshelf.settings

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class BookshelfInputViewModelTest {
    @Test fun textSelectionAndOriginalImportGroupRestoreExactly() {
        val saved = SavedStateHandle(mapOf<String, Any>("kind" to 1, "groupId" to 8L)); val model = BookshelfInputViewModel(saved)
        model.edit("[\"book\"]", 2, 6)
        val restored = BookshelfInputViewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(BookshelfInputState("[\"book\"]", 2, 6), restored.state.value)
        assertEquals(BookshelfInputResult("[\"book\"]", 8), restored.confirm())
        assertNull(restored.confirm()); assertNull(restored.selectFile())
    }
    @Test fun filePickerAndCancelConsumeTheDraftWithoutConfirmingOrWriting() {
        val model = BookshelfInputViewModel(SavedStateHandle(mapOf("groupId" to 4L)))
        model.edit("draft", 3, 3); assertEquals(4L, model.selectFile()); assertNull(model.confirm())
        val cancelled = BookshelfInputViewModel(SavedStateHandle()); cancelled.edit("url", 9, -1)
        assertEquals(BookshelfInputState("url", 3, 0), cancelled.state.value)
        cancelled.cancel(); assertNull(cancelled.confirm()); assertNull(cancelled.selectFile())
    }
    @Test fun exportCopiesActualUriAndFinishedStateSurvivesRecreation() {
        val saved = SavedStateHandle(mapOf("kind" to 2, "value" to "content://result"))
        val model = BookshelfInputViewModel(saved); model.edit("Edited display", 0, 0)
        assertEquals("content://result", model.confirm()?.text)
        val restored = BookshelfInputViewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertNull(restored.confirm())
    }
}
