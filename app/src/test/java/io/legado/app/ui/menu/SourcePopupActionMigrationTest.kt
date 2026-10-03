package io.legado.app.ui.menu

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePopupActionMigrationTest {

    @Test
    fun `source related row menus use the shared vertical builder`() {
        sourceMenuFiles.forEach { path ->
            val source = readProjectFile(path)
            assertFalse(
                "$path should not import platform PopupMenu",
                source.contains("import android.widget.PopupMenu"),
            )
            assertFalse(
                "$path should not import AppCompat PopupMenu",
                source.contains("import androidx.appcompat.widget.PopupMenu"),
            )
            assertContains(path, source, "DropdownMenu(menu")
        }

        assertContains(EXPLORE, readProjectFile(EXPLORE), "MaterialTheme.colorScheme.error")
        legacyMenuFiles.forEach { path ->
            assertFalse(
                "$path should be removed",
                sequenceOf(File(path), File("app/$path")).any(File::isFile),
            )
        }
    }

    @Test
    fun `dynamic source menu entries keep their visibility and labels`() {
        org.junit.Assert.assertEquals(
            listOf("edit", "top", "search", "refresh", "delete"),
            io.legado.app.ui.main.explore.visibleExploreHomeRowActions(false),
        )
        org.junit.Assert.assertEquals(
            listOf("edit", "top", "login", "search", "refresh", "delete"),
            io.legado.app.ui.main.explore.visibleExploreHomeRowActions(true),
        )
    }

    @Test
    fun `source menu labels keep their previous order`() {
        org.junit.Assert.assertEquals(
            listOf("Top", "Bottom", "Edit", "Disable", "Delete"),
            io.legado.app.ui.book.changesource.chapterSourceRowActions.map { it.name },
        )
        org.junit.Assert.assertEquals(
            listOf("Top", "Bottom", "Edit", "Disable", "Delete"),
            io.legado.app.ui.book.changesource.bookSourceRowActions.map { it.name },
        )
        assertOrdered(
            EXPLORE,
            "R.string.edit",
            "R.string.to_top",
            "R.string.login",
            "R.string.search",
            "R.string.refresh",
            "R.string.delete",
        )
    }

    @Test
    fun `source menu callbacks and delete side effects are preserved`() {
        // Compose manager row actions are covered by BookSourceManagerViewModelTest.
        // Book callbacks and delete confirmation: BookSourceViewModelTest/BookSourceComposeTest.
        // Chapter callback ordering is covered independently by
        // ChapterSourceViewModelTest/ChapterSourceComposeTest.
        assertActions(
            "src/main/java/io/legado/app/ui/main/explore/ExploreHomeRoute.kt",
            "model.top(url)",
            "model.requestDelete(url)",
            "model.refresh(url)",
        )
        assertActions(
            "src/main/java/io/legado/app/ui/main/explore/ExploreFragment.kt",
            "startActivity<BookSourceEditActivity>",
            "startActivity<SourceLoginActivity>",
            "SearchActivity.start",
        )
    }

    private fun assertActions(path: String, vararg expected: String) {
        val source = readProjectFile(path)
        expected.forEach { assertContains(path, source, it) }
    }

    private fun assertOrdered(path: String, vararg expected: String) {
        val source = readProjectFile(path)
        var previous = -1
        expected.forEach { snippet ->
            val current = source.indexOf(snippet, previous + 1)
            assertTrue("$path should contain $snippet after the previous item", current > previous)
            previous = current
        }
    }

    private fun assertContains(path: String, source: String, expected: String) {
        assertTrue("$path should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()

    private companion object {
        const val EXPLORE = "src/main/java/io/legado/app/ui/main/explore/ExploreHomeScreen.kt"
        val sourceMenuFiles = listOf(EXPLORE)
        val legacyMenuFiles =
            listOf(
                "src/main/res/menu/book_source_item.xml",
                "src/main/res/menu/change_source_item.xml",
                "src/main/res/menu/explore_item.xml",
                "src/main/res/menu/rss_main_item.xml",
            )
    }
}
