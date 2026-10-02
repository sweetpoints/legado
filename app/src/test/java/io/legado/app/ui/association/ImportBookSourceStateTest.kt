package io.legado.app.ui.association

import io.legado.app.ui.widget.dialog.resolveCodeDialogOriginal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun `book source replacement preview is isolated from other import dialogs`() {
        val codeDialog = readProjectFile(
            "src/main/java/io/legado/app/ui/widget/dialog/CodeDialog.kt"
        )
        assertTrue(codeDialog.contains("binding.codeView.keyListener = null"))
        assertFalse(codeDialog.contains("ReplaceRuleActivity"))
        assertTrue(codeDialog.contains("callback()?.onOpenReplaceRules()"))
        assertTrue(codeDialog.contains("fun setReplaceRuleRefreshPending"))
        assertTrue(codeDialog.contains("if (!replaceRuleRefreshPending)"))
        assertTrue(codeDialog.contains("binding.codeView.keyListener = if (pending"))
        assertTrue(codeDialog.contains("isCancelable = !pending"))
        assertTrue(codeDialog.contains("fun clearAlternateCode()"))
        assertTrue(codeDialog.contains("callback()?.isReplaceRuleRefreshPending()"))
        val replaceMenu = codeDialog.substringAfter("R.id.menu_replace_rule ->")
            .substringBefore("R.id.menu_save ->")
        assertTrue(replaceMenu.contains("onOpenReplaceRules"))
        assertFalse(replaceMenu.contains("dismiss"))
        assertTrue(codeDialog.contains("fun refreshAlternateCode()"))
        assertTrue(codeDialog.contains("callback()?.getCodeAlternate(requestId)"))
        assertTrue(codeDialog.contains("!replaceRuleRefreshPending"))
        assertTrue(
            codeDialog.indexOf("setOnCheckedChangeListener") >
                codeDialog.indexOf("val canPreviewReplacement")
        )
        assertTrue(codeDialog.contains("initMenu(!disableEdit)"))
        assertTrue(codeDialog.contains("saveEnabled && (!show || sourcePreview) && searchView.isIconified"))
        assertTrue(codeDialog.contains("editorReadOnly = showingAlternate && !sourcePreview"))
        assertTrue(codeDialog.contains("callback()?.onCodeSave(currentOriginalCode(), requestId)"))
        assertTrue(codeDialog.contains("findTextRanges("))
        assertTrue(codeDialog.contains("right - left - navigationWidth"))
        assertTrue(
            codeDialog.contains(
                "binding.toolBar.contentInsetStart - binding.toolBar.contentInsetEnd"
            )
        )
        assertTrue(
            codeDialog.contains("binding.toolBar.paddingStart - binding.toolBar.paddingEnd")
        )
        assertTrue(
            codeDialog.contains("updateSearch(keepIndex = true, selectMatch = false)")
        )
        assertTrue(codeDialog.contains("if (selectMatch) showCurrentMatch()"))
        val alternatePreview = codeDialog.substringAfter("private fun showAlternate")
            .substringBefore("private fun initMenu")
        assertTrue(
            alternatePreview.contains("if (!searchView.isIconified) showCurrentMatch()")
        )
        assertTrue(
            codeDialog.contains("R.id.menu_search_previous -> moveToMatch(searchIndex - 1)")
        )
        assertTrue(
            codeDialog.contains("R.id.menu_search_next -> moveToMatch(searchIndex + 1)")
        )
        assertTrue(codeDialog.contains("codeView.setSelection(range.first, range.last + 1)"))
        assertTrue(codeDialog.contains("codeView.bringPointIntoView(range.first)"))
        assertTrue(codeDialog.contains("searchRanges.getOrNull(searchIndex) != range"))
        assertTrue(codeDialog.contains("override fun onViewStateRestored"))
        assertTrue(codeDialog.contains("savedInstanceState?.getString(\"originalCode\")"))
        assertTrue(codeDialog.contains("saveStateData(originalCodeStateKey, currentOriginalCode())"))
        assertTrue(codeDialog.contains("key?.also { IntentData.put(it, data) }"))
        assertTrue(codeDialog.contains("originalCodeStateKey?.let { IntentData.get<Any>(it) }"))
        assertTrue(codeDialog.contains("outState.putBoolean(\"showingAlternate\""))
        assertTrue(
            codeDialog.contains(
                "if (!searchView.isIconified) updateSearch(keepIndex = true)"
            )
        )

        val codeMenu = readProjectFile("src/main/res/menu/code_edit.xml")
        assertTrue(codeMenu.contains("@+id/menu_search"))
        assertTrue(codeMenu.contains("@+id/menu_search_previous"))
        assertTrue(codeMenu.contains("@+id/menu_search_next"))
        assertTrue(codeMenu.contains("@+id/menu_replace_rule"))

        // RSS's mutable legacy source assertions are covered by RssImportViewModelTest
        // and the real mixed Book/RSS CodeSelectionUiTest callbacks.
        assertEquals(io.legado.app.R.string.import_status_new, rssImportStatus(io.legado.app.data.repository.RssImportStatus.New))
        assertEquals(io.legado.app.R.string.import_status_update, rssImportStatus(io.legado.app.data.repository.RssImportStatus.Update))
        assertEquals(io.legado.app.R.string.import_status_exist, rssImportStatus(io.legado.app.data.repository.RssImportStatus.Existing))
        assertEquals(io.legado.app.R.string.import_status_error, rssImportStatus(io.legado.app.data.repository.RssImportStatus.Error))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
    @Test
    fun `reimport explicitly selects same timestamp source while allowing cancellation`() {
        val same = ImportBookSourceStatus(isNew = false, isUpdate = false)
        assertTrue(resolveImportSourceSelection(same, manualSelection = null, selectExisting = true))
        assertFalse(resolveImportSourceSelection(same, manualSelection = false, selectExisting = true))
    }

}
