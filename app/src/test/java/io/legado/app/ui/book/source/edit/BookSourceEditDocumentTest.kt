package io.legado.app.ui.book.source.edit

import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceEditDocumentTest {
    @Test
    fun selectionAndUiPreferencesDoNotMarkSourceDirty() {
        val original = BookSourceEditDocument.from(BookSource("url", "name"))
        val selected =
            original.copy(
                form = original.form.updateField(0, "bookSourceUrl", "url", 1, 2),
                selectedTab = 4,
                optionsExpanded = true,
                autoComplete = true,
            )
        assertFalse(selected.dirty())
        assertTrue(
            selected.copy(form = selected.form.updateField(0, "bookSourceName", "changed")).dirty()
        )
    }

    @Test
    fun eventListenerAndCustomButtonChangesAreIncludedInDirtyState() {
        val original = BookSourceEditDocument.from(BookSource("url", "name"))
        assertTrue(
            original
                .copy(
                    form =
                        original.form.copy(
                            options = original.form.options.copy(eventListener = true)
                        )
                )
                .dirty()
        )
        assertTrue(
            original
                .copy(
                    form =
                        original.form.copy(
                            options = original.form.options.copy(customButton = true)
                        )
                )
                .dirty()
        )
    }

    @Test
    fun fullPrivateDraftRoundTripsPayloadSelectionMetadataAndDelivery() {
        val source = BookSource("url", "name", customOrder = 71, lastUpdateTime = 99)
        val original = BookSourceEditDocument.from(source)
        val script = "@js:'🦉';\r\n".repeat(10_000)
        val document =
            original.copy(
                form = original.form.updateField(1, "bookList", script, 17, 6),
                delivery = BookSourceSaveDelivery("ticket", BookSourceSaveAction.DEBUG, "url"),
                revision = 14,
            )
        val json = GSON.toJson(document)
        val restored = GSON.fromJsonObject<BookSourceEditDocument>(json).getOrThrow()
        assertEquals(document, restored)
        assertEquals(71, restored.original().customOrder)
        assertEquals(99L, restored.original().lastUpdateTime)
        assertTrue(json.contains("\"selectionStart\""))
        assertTrue(json.contains("\"DEBUG\""))
        assertEquals(script, restored.source().ruleSearch!!.bookList)
    }
}
