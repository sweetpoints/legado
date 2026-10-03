package io.legado.app.ui.book.source.edit

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceEditLayoutTest {
    @Test
    fun allFiveTypesAndSixOptionsRoundTripThroughCompleteForm() {
        (0..4).forEach { type ->
            val source =
                BookSource(
                    "url",
                    "name",
                    bookSourceType = type,
                    enabled = false,
                    enabledExplore = false,
                    enabledCookieJar = false,
                    eventListener = true,
                    customButton = true,
                )
            val form = projectBookSourceEditForm(source)
            val saved = materializeBookSourceEditForm(source, form)
            assertEquals(type, saved.bookSourceType)
            assertFalse(saved.enabled)
            assertFalse(saved.enabledExplore)
            assertFalse(saved.enabledCookieJar!!)
            assertTrue(saved.eventListener)
            assertTrue(saved.customButton)
        }
    }

    @Test
    fun loginUiCapabilityUsesOriginalBlankFormSemantics() {
        val form = projectBookSourceEditForm(BookSource("url", "name"))
        assertFalse(form.hasLogin())
        assertFalse(form.updateField(0, "loginUi", " [ ] ").hasLogin())
        assertTrue(form.updateField(0, "loginUi", "[{\"name\":\"user\"}]").hasLogin())
        assertTrue(form.updateField(0, "loginUrl", "https://login.invalid").hasLogin())
    }
}
