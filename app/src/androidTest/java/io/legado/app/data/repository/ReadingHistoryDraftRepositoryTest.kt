package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadingHistoryDraftRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun atomicDraftRestoresLargeSearchAuthorSetAndNavigationAcrossRepositoryInstances() =
        runBlocking {
            val first = FileReadingHistoryDraftRepository(context)
            val second = FileReadingHistoryDraftRepository(context)
            val ticket = first.create()
            try {
                val draft =
                    ReadingHistoryDraft(
                        1,
                        "search".repeat(50_000),
                        ReadingHistoryConfirmation(
                            ReadingHistoryIdentity("Title".repeat(30_000), "作者".repeat(50_000)),
                            "作者",
                        ),
                        ReadingHistoryNavigation(
                            "delivery",
                            ReadingHistoryDestination(ReadingHistoryReader.Audio, "url", "Title"),
                        ),
                    )
                first.write(ticket, draft)
                assertEquals(draft, second.read(ticket))
            } finally {
                first.release(ticket)
            }
        }

    @Test
    fun revisionFencingAndReleasePreventOldOwnerFromRecreatingDeletedDraft() = runBlocking {
        val first = FileReadingHistoryDraftRepository(context)
        val second = FileReadingHistoryDraftRepository(context)
        val ticket = first.create()
        try {
            second.write(ticket, ReadingHistoryDraft(3, "current"))
            first.write(ticket, ReadingHistoryDraft(1, "stale"))
            assertEquals("current", first.read(ticket).query)
            second.release(ticket)
            assertTrue(
                runCatching { first.write(ticket, ReadingHistoryDraft(4, "resurrection")) }
                    .isFailure
            )
            assertTrue(runCatching { second.read(ticket) }.isFailure)
        } finally {
            first.release(ticket)
        }
    }
}
