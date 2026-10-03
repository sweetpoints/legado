package io.legado.app.ui.book.manga

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.MangaNativeKind
import io.legado.app.data.repository.MangaNativeRequest
import io.legado.app.model.ReadBook
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaBrowserNavigationTest {
    @Test
    fun usesMangaSourceKindAndPreservesCompleteBrowserPayload() {
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
            val intent =
                mangaChapterBrowserIntent(
                    ApplicationProvider.getApplicationContext<Context>(),
                    request,
                )
            assertEquals(SourceType.book, intent.getIntExtra("sourceType", -1))
            assertEquals(mangaSource.bookSourceUrl, intent.getStringExtra("sourceOrigin"))
            assertEquals(mangaSource.bookSourceName, intent.getStringExtra("sourceName"))
            assertEquals("Full chapter title", intent.getStringExtra("title"))
            assertEquals(url, intent.getStringExtra("url"))
        } finally {
            ReadBook.bookSource = savedTextSource
        }
    }
}
