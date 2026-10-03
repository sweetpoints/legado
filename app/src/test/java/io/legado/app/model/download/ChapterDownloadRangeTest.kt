package io.legado.app.model.download

import org.junit.Assert.*
import org.junit.Test

class ChapterDownloadRangeTest {
    @Test
    fun bookEmptyBoundsRetainExistingMinusOneAndTotalFallback() {
        assertEquals(
            ChapterDownloadRange(-1, 16),
            chapterDownloadRange(ChapterDownloadMode.Book, "", "", 17),
        )
        assertEquals(
            ChapterDownloadRange(3, 16),
            chapterDownloadRange(ChapterDownloadMode.Book, "4", "", 17),
        )
    }

    @Test
    fun bookExplicitRangesRemainUnclampedIncludingReversedBounds() {
        assertEquals(
            ChapterDownloadRange(4, 1),
            chapterDownloadRange(ChapterDownloadMode.Book, "5", "2", 3),
        )
        assertEquals(
            ChapterDownloadRange(0, 99998),
            chapterDownloadRange(ChapterDownloadMode.Book, "1", "99999", 3),
        )
    }

    @Test
    fun audioUsesExistingPolicyAndRejectsMissingReversedAndOutOfBoundsStarts() {
        assertEquals(
            ChapterDownloadRange(2, 4),
            chapterDownloadRange(ChapterDownloadMode.Audio, "3", "99", 5),
        )
        listOf("" to "3", "2" to "", "4" to "2", "6" to "6").forEach { (first, last) ->
            assertNull(chapterDownloadRange(ChapterDownloadMode.Audio, first, last, 5))
        }
    }

    @Test
    fun malformedInputsCannotDispatchEitherNativeOperation() {
        ChapterDownloadMode.entries.forEach { mode ->
            assertNull(chapterDownloadRange(mode, "invalid", "1", 3))
            assertNull(chapterDownloadRange(mode, "1", "invalid", 3))
        }
    }
}
