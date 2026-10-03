package io.legado.app.ui.book.source.edit

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceEditRedirectTest {
    @Test
    fun privateDocumentUsesOriginalBlankScriptRedirectSemantics() {
        assertFalse(BookSourceEditDocument.from(BookSource("url", "name", mainJs = " ")).redirectJs)
        assertTrue(
            BookSourceEditDocument.from(BookSource("url", "name", mainJs = "const config = {};"))
                .redirectJs
        )
    }
}
