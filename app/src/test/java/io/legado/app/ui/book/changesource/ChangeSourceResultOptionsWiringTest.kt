package io.legado.app.ui.book.changesource

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChangeSourceResultOptionsWiringTest {
    @Test
    fun bothComposeMenusKeepTheSharedResultControlsInTheirOriginalOrder() {
        assertEquals(
            listOf(
                "Manage",
                "Refresh",
                "Author",
                "WordCount",
                "ResponseTime",
                "WordCountFilter",
                "Info",
                "Toc",
                "Group",
                "Close",
            ),
            bookSourceMenuOrder.map { it.name },
        )
        assertEquals(
            listOf(
                "Manage",
                "Automation",
                "Refresh",
                "Author",
                "WordCount",
                "ResponseTime",
                "WordCountFilter",
                "Info",
                "Toc",
                "Group",
                "Close",
            ),
            chapterSourceMenuOrder.map { it.name },
        )
    }

    @Test
    fun resultOptionsDoNotImplicitlyChangeTheBaseWordCountPreference() {
        val path = "src/main/java/io/legado/app/help/config/AppConfig.kt"
        val config = sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
        assertFalse(config.contains("if (value) changeSourceLoadWordCount = true"))
        assertFalse(config.contains("if (mode != 0) changeSourceLoadWordCount = true"))
    }
    // Real sorting/reference policies: BookSourceSearchRepositoryTest,
    // ChapterSourceSearchRepositoryTest.
    // Cached and deferred measurement actions: BookSourceViewModelTest, ChapterSourceViewModelTest.
}
