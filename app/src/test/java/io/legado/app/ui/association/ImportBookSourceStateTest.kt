package io.legado.app.ui.association

import io.legado.app.ui.widget.dialog.resolveCodeDialogOriginal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportBookSourceStateTest {

    @Test
    fun `replace manager refresh keeps the editable source draft`() {
        assertEquals(
            "edited original",
            resolveCodeDialogOriginal(true, "edited original", "replacement preview"),
        )
        assertEquals(
            "visible edit",
            resolveCodeDialogOriginal(false, "older original", "visible edit"),
        )
    }

    @Test
    fun `classifies new updated and existing sources`() {
        assertEquals(
            ImportBookSourceStatus(isNew = true, isUpdate = false),
            resolveImportBookSourceStatus(importedLastUpdateTime = 100, localLastUpdateTime = null),
        )
        assertEquals(
            ImportBookSourceStatus(isNew = false, isUpdate = true),
            resolveImportBookSourceStatus(importedLastUpdateTime = 101, localLastUpdateTime = 100),
        )
        assertEquals(
            ImportBookSourceStatus(isNew = false, isUpdate = false),
            resolveImportBookSourceStatus(importedLastUpdateTime = 100, localLastUpdateTime = 100),
        )
        assertEquals(
            ImportBookSourceStatus(isNew = false, isUpdate = false),
            resolveImportBookSourceStatus(importedLastUpdateTime = 99, localLastUpdateTime = 100),
        )
    }

    @Test
    fun `default selection follows source status`() {
        val update = ImportBookSourceStatus(isNew = false, isUpdate = true)
        val existing = ImportBookSourceStatus(isNew = false, isUpdate = false)

        assertTrue(resolveImportSourceSelection(update, manualSelection = null))
        assertFalse(resolveImportSourceSelection(existing, manualSelection = null))
    }

    @Test
    fun `manual selection override survives repeated status changes`() {
        val newSource = ImportBookSourceStatus(isNew = true, isUpdate = false)
        val existing = ImportBookSourceStatus(isNew = false, isUpdate = false)

        assertFalse(resolveImportSourceSelection(newSource, manualSelection = false))
        assertFalse(resolveImportSourceSelection(existing, manualSelection = false))
        assertFalse(resolveImportSourceSelection(newSource, manualSelection = false))
        assertTrue(resolveImportSourceSelection(existing, manualSelection = true))
    }

    @Test
    fun `book import status labels resolve localized resources`() {
        assertEquals(io.legado.app.R.string.import_status_new, bookImportStatus(io.legado.app.data.repository.BookImportStatus.New))
        assertEquals(io.legado.app.R.string.import_status_update, bookImportStatus(io.legado.app.data.repository.BookImportStatus.Update))
        assertEquals(io.legado.app.R.string.import_status_exist, bookImportStatus(io.legado.app.data.repository.BookImportStatus.Existing))
        assertEquals(io.legado.app.R.string.import_status_error, bookImportStatus(io.legado.app.data.repository.BookImportStatus.Error))
    }

    @Test
    fun `rss status labels preserve all import states`() {
        // RSS's mutable legacy source assertions are covered by RssImportViewModelTest
        // and the real mixed Book/RSS CodeSelectionUiTest callbacks.
        assertEquals(io.legado.app.R.string.import_status_new, rssImportStatus(io.legado.app.data.repository.RssImportStatus.New))
        assertEquals(io.legado.app.R.string.import_status_update, rssImportStatus(io.legado.app.data.repository.RssImportStatus.Update))
        assertEquals(io.legado.app.R.string.import_status_exist, rssImportStatus(io.legado.app.data.repository.RssImportStatus.Existing))
        assertEquals(io.legado.app.R.string.import_status_error, rssImportStatus(io.legado.app.data.repository.RssImportStatus.Error))
    }

    @Test
    fun `reimport explicitly selects same timestamp source while allowing cancellation`() {
        val same = ImportBookSourceStatus(isNew = false, isUpdate = false)
        assertTrue(resolveImportSourceSelection(same, manualSelection = null, selectExisting = true))
        assertFalse(resolveImportSourceSelection(same, manualSelection = false, selectExisting = true))
    }

}
