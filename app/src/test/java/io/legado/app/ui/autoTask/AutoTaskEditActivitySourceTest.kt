package io.legado.app.ui.autoTask

import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.data.repository.*
import org.junit.Assert.*
import org.junit.Test

/** Pure editor contracts; rendering and back/navigation are exercised by Compose/host tests. */
class AutoTaskEditActivitySourceTest {
    @Test fun cursorAndTrimEquivalentInputAreNotUnsavedContentChanges() {
        val baseline = AutoTaskEditorDraft.from(AutoTaskRule(name = "name", script = "body", comment = "comment"))
        val cursor = baseline.with(AutoTaskEditorField.Script, AutoTaskEditorText("body", 3))
        assertTrue(cursor.sameContent(baseline))
        assertTrue(cursor.with(AutoTaskEditorField.Name, AutoTaskEditorText(" name ")).sameContent(baseline))
        assertFalse(cursor.with(AutoTaskEditorField.Script, AutoTaskEditorText("body ")).sameContent(baseline))
    }
    @Test fun fullEditorSupportsAllSixOriginalCodeFieldsAndBoundsReturnedSelection() {
        assertEquals(setOf(AutoTaskEditorField.Script, AutoTaskEditorField.Header, AutoTaskEditorField.JsLib,
            AutoTaskEditorField.LoginUrl, AutoTaskEditorField.LoginUi, AutoTaskEditorField.LoginCheckJs), AutoTaskEditorField.entries.filter { it.code }.toSet())
        val draft = AutoTaskEditorDraft().with(AutoTaskEditorField.LoginCheckJs, AutoTaskEditorText("text", -5, 100))
        assertEquals(AutoTaskEditorText("text", 0, 4), draft[AutoTaskEditorField.LoginCheckJs])
    }
    @Test fun loginUrlRetainsMultipleLinesAndExactCursor() {
        val text = "https://host/\n@js: value"
        val draft = AutoTaskEditorDraft().with(AutoTaskEditorField.LoginUrl, AutoTaskEditorText(text, 14))
        assertEquals(text, draft.entity("id").loginUrl); assertEquals(14, draft[AutoTaskEditorField.LoginUrl].start)
    }
    @Test fun draftAppliesEveryEditableFieldWhileKeepingOriginalIdentityAndFreshRuntime() {
        val imported = AutoTaskRule("foreign", "name", false, "0 * * * *", "url", "ui", "check", "comment", "script", "header", "lib", "2", false)
        val fresh = AutoTaskRule("original", customOrder = 20, lastRunAt = 90, lastLog = "latest")
        val result = AutoTaskEditorDraft.from(imported).entity("original", fresh)
        assertEquals(imported.copy(id = "original", customOrder = 20, lastRunAt = 90, lastLog = "latest"), result)
    }
}
