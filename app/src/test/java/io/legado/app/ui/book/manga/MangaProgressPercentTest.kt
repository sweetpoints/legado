package io.legado.app.ui.book.manga

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaProgressPercentTest {
    @Test
    fun roundedHundredRemainsBelowCompletionUntilFinalImage() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("99.9%", mangaProgressPercent(9999, 10000, 0, 2))
            assertEquals("100.0%", mangaProgressPercent(9999, 10000, 1, 2))
            assertEquals("0.0%", mangaProgressPercent(0, 0, 0, 0))
            assertEquals("50.0%", mangaProgressPercent(1, 4, 0, 0))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
