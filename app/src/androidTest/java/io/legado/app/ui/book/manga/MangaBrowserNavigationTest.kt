package io.legado.app.ui.book.manga

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.AppBrowserNavigationStore
import io.legado.app.data.repository.MangaNativeKind
import io.legado.app.data.repository.MangaNativeRequest
import io.legado.app.model.ReadBook
import io.legado.app.model.browser.BrowserRequest
import io.legado.app.ui.browser.BrowserNavigation
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MangaBrowserNavigationTest {
    @Test
    fun chapterBrowserKeepsFullPayloadPrivateAndCarriesOnlyAnOpaqueTicket() = runBlocking {
        val mangaSource =
            BookSource(
                bookSourceUrl = "https://manga.invalid/source",
                bookSourceName = "Manga source",
                bookSourceType = BookSourceType.image,
            )
        val savedTextSource = ReadBook.bookSource
        try {
            ReadBook.bookSource = BookSource(bookSourceUrl = "https://text.invalid/source")
            val url = "https://manga.invalid/chapter/" + "opaque".repeat(400000) + ",{header:full}"
            val request =
                MangaNativeRequest(
                    ticket = UUID.randomUUID().toString(),
                    kind = MangaNativeKind.ChapterBrowser,
                    imageUrl = url,
                    title = "Full chapter title",
                    sourceOrigin = mangaSource.bookSourceUrl,
                    sourceName = mangaSource.bookSourceName,
                    sourceType = mangaBrowserSourceKind(mangaSource),
                )
            assertEquals(SourceType.book, mangaBrowserSourceKind(mangaSource))
            val context = ApplicationProvider.getApplicationContext<Context>()
            val ticket =
                withContext(Dispatchers.IO) {
                    BrowserNavigation.prepare(context, mangaChapterBrowserRequest(request))
                }
            try {
                val intent = BrowserNavigation.intent(context, ticket)
                assertEquals(setOf(BrowserNavigation.PREPARED_TICKET), intent.extras!!.keySet())
                assertFalse(intent.hasExtra("url"))
                assertFalse(intent.hasExtra("sourceOrigin"))
                assertEquals(
                    BrowserRequest(
                        url = url,
                        title = "Full chapter title",
                        sourceOrigin = mangaSource.bookSourceUrl,
                        sourceName = mangaSource.bookSourceName,
                        sourceType = SourceType.book,
                    ),
                    withContext(Dispatchers.IO) {
                        AppBrowserNavigationStore(context).read(ticket)
                    },
                )
            } finally {
                withContext(Dispatchers.IO) { AppBrowserNavigationStore(context).abandon(ticket) }
            }
        } finally {
            ReadBook.bookSource = savedTextSource
        }
    }
}
