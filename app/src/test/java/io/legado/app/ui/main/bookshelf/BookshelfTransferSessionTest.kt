package io.legado.app.ui.main.bookshelf

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class BookshelfTransferSessionTest {
    @Test
    fun staleProgressCompletionAndRepeatedCancelCannotOverwriteNextOperation() {
        val session = BookshelfTransferSession(SavedStateHandle())
        val first = session.beginAdd()
        session.progress(first, 3)
        assertEquals(3, session.addProgress.value)
        val second = session.beginAdd()
        session.progress(first, 99)
        session.finish(first)
        assertEquals(0, session.addProgress.value)
        session.progress(second, 4)
        session.finish(second)
        session.progress(second, 8)
        assertEquals(-1, session.addProgress.value)
        session.cancelAdd()
        session.cancelAdd()
        val third = session.beginAdd()
        session.finish(second)
        session.progress(third, 2)
        assertEquals(2, session.addProgress.value)
    }

    @Test
    fun pendingExportRestoresAndOnlyMatchingAcknowledgmentConsumesIt() {
        val saved = SavedStateHandle()
        val session = BookshelfTransferSession(saved)
        val old = session.exportReady("old")
        val current = session.exportReady("new")
        session.exportLaunched("old", old)
        session.exportReturned("old", old)
        assertEquals("new", session.pendingExport.value)
        val restored =
            BookshelfTransferSession(
                SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            )
        assertEquals("new", restored.pendingExport.value)
        restored.exportLaunched("new", current)
        restored.exportReturned("new", "stale")
        assertEquals("new", restored.pendingExport.value)
        restored.exportReturned("new", current)
        restored.exportReturned("new", current)
        assertNull(restored.pendingExport.value)
    }

    @Test
    fun pickerUsesOriginalGroupAcrossRecreationAndResultIsConsumedOnce() {
        val saved = SavedStateHandle()
        val requestId = BookshelfTransferSession(saved).importRequested(8)
        val restored =
            BookshelfTransferSession(
                SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            )
        assertNull(restored.importReturned("stale"))
        assertEquals(8L, restored.importReturned(requestId))
        assertNull(restored.importReturned(requestId))
        assertEquals(
            -1,
            restored.addProgress.value,
        ) // A dead process never restores an orphan running dialog.
    }

    @Test
    fun fileReadRequestRestoresAndOldCompletionCannotClearNewRequest() {
        val saved = SavedStateHandle()
        val session = BookshelfTransferSession(saved)
        val first = session.fileReady("content://same", 4)
        val second = session.fileReady("content://same", 4)
        assertNotEquals(first.id, second.id)
        session.fileFinished(first.id)
        val restored =
            BookshelfTransferSession(
                SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            )
        assertEquals(second, restored.pendingFileImport.value)
        restored.fileFinished(second.id)
        assertNull(restored.pendingFileImport.value)
    }
}
