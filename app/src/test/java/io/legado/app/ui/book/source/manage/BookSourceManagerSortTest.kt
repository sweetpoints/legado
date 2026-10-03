package io.legado.app.ui.book.source.manage

import org.junit.Assert.assertEquals
import org.junit.Test

class BookSourceManagerSortTest {
    @Test
    fun manualOrderKeepsDaoTieOrderAndDescendingReversesTheWholeSequence() {
        val rows = listOf(row("first", 100), row("second", 100), row("last", 200))
        assertEquals(listOf("first", "second", "last"), urls(rows, BookSourceSort.Default, true))
        assertEquals(listOf("last", "second", "first"), urls(rows, BookSourceSort.Default, false))
    }

    @Test
    fun updateTimeDefaultDirectionIsNewestFirst() {
        val rows = listOf(row("older", 0).copy(updated = 10), row("newer", 1).copy(updated = 20))
        assertEquals(listOf("newer", "older"), urls(rows, BookSourceSort.Update, true))
        assertEquals(listOf("older", "newer"), urls(rows, BookSourceSort.Update, false))
    }

    @Test
    fun enabledDescendingKeepsNamesAscendingForMatchingEnabledValues() {
        val rows =
            listOf(
                row("enabled", 0),
                row("Zulu", 1).copy(enabled = false),
                row("Alpha", 2).copy(enabled = false),
            )
        assertEquals(listOf("Alpha", "Zulu", "enabled"), urls(rows, BookSourceSort.Enable, false))
    }

    @Test
    fun domainGroupingPutsUnknownHostLastAndUsesNewestFirstWithinEachHost() {
        val rows =
            listOf(
                row("unknown", 0),
                row("older", 1).copy(host = "a.example", updated = 10),
                row("newer", 2).copy(host = "a.example", updated = 20),
                row("other", 3).copy(host = "b.example", updated = 30),
            )
        val expected = listOf("newer", "older", "other", "unknown")
        assertEquals(
            expected,
            sortSourceManagerRows(rows, BookSourceSort.Name, true, true).map { it.url },
        )
        assertEquals(
            expected,
            sortSourceManagerRows(rows, BookSourceSort.Default, false, true).map { it.url },
        )
    }

    private fun urls(
        rows: List<SourceManagerRow>,
        sort: BookSourceSort,
        ascending: Boolean,
    ): List<String> {
        return sortSourceManagerRows(rows, sort, ascending, false).map { it.url }
    }

    private fun row(url: String, order: Int): SourceManagerRow {
        return SourceManagerRow(
            url = url,
            name = url,
            group = null,
            order = order,
            enabled = true,
            exploreEnabled = true,
            hasExplore = false,
            hasLogin = false,
            hasJs = false,
            updated = 0,
            response = 0,
            weight = 0,
            checkStatus = "NEEDS_CHECK",
            checkDetail = "",
            host = "#",
        )
    }
}
