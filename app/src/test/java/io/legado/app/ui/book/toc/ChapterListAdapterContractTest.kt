package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookChapter
import io.legado.app.model.book.toc.*
import org.junit.Assert.*
import org.junit.Test

/** Stable identities and fold restoration survive identical display titles. Async title races are in TocChapterViewModelTest. */
class ChapterListAdapterContractTest {
    @Test fun identicalTitlesKeepDistinctVolumeAndReadingKeysAcrossSearch() {
        val chapters = listOf(BookChapter(index = 0, title = "Same", isVolume = true), BookChapter(index = 1, title = "Same"), BookChapter(index = 2, title = "Same"))
        val state = TocListState(); state.setFullChapters(chapters, false)
        assertEquals(listOf("volume:0", "chapter:1", "chapter:2"), state.showNormal(1).map { it.key })
        assertEquals(listOf("volume:0", "chapter:2"), state.showSearch(listOf(2), 1).map { it.key })
    }
    @Test fun collapseCheckpointUsesOnlyExistingParentIdentitiesAndLocateExpandsCurrentPath() {
        val chapters = listOf(BookChapter(index = 0, isVolume = true), BookChapter(index = 1), BookChapter(index = 2, isVolume = true), BookChapter(index = 3))
        val state = TocListState(); state.setFullChapters(chapters, false); state.restoreCollapsed(setOf(0, 2, 99))
        assertEquals(setOf(0, 2), state.collapsedIndexes()); assertEquals(listOf("volume:0", "volume:2"), state.showNormal(1).map { it.key })
        assertTrue(state.expandVolumeContainingChapter(1)); assertEquals(listOf("volume:0", "chapter:1", "volume:2"), state.showNormal(1).map { it.key })
    }
}
