package io.legado.app.data.repository

import android.os.Looper
import io.legado.app.help.SourceSharePassphrase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RssSourceManagementSharingRepositoryTest {
    @Test
    fun realPassphraseFormatKeepsRssTypeAndUploadExpiryOnIo() = runBlocking {
        var summaryThread: Looper? = Looper.getMainLooper()
        var expiryThread: Looper? = Looper.getMainLooper()
        val repo =
            AppRssSourceManagementSharingRepository(
                summary = {
                    summaryThread = Looper.myLooper()
                    "Upload expiry"
                },
                expiryDays = {
                    expiryThread = Looper.myLooper()
                    0
                },
            )
        val url = "https://example.invalid/rss.json"
        val feedback = repo.feedback(url)
        assertEquals(url, feedback.url)
        assertEquals("Upload expiry", feedback.summary)
        assertTrue(feedback.canEncode)
        val decoded =
            SourceSharePassphrase.decode(repo.passphrase(url))
                as SourceSharePassphrase.DecodeResult.Success
        assertEquals(url, decoded.value.url)
        assertEquals(SourceSharePassphrase.Type.RSS_SOURCE, decoded.value.type)
        assertEquals(0L, decoded.value.expiresAt)
        assertNotSame(Looper.getMainLooper(), summaryThread)
        assertNotSame(Looper.getMainLooper(), expiryThread)
    }

    @Test
    fun fileExportSkipsUploadMetadataAndCannotOfferNetworkPassphrase() = runBlocking {
        val repo =
            AppRssSourceManagementSharingRepository(
                summary = { error("Must not consult upload settings") }
            )
        val feedback = repo.feedback("content://exports/source.json")
        assertEquals(RssSourceManagementShareFeedback("content://exports/source.json"), feedback)
    }
}
