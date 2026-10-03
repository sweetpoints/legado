package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.browser.BrowserRequest
import io.legado.app.ui.browser.BrowserNavigation
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBrowserNavigationStoreTest {
    @Test
    fun preparedLargeHtmlStaysPrivateAndRestoresThroughAnOpaqueIntentTicket() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "browser-navigation")
        val store = AppBrowserNavigationStore(context)
        val request =
            BrowserRequest(
                url = "https://prepared.invalid",
                title = "Prepared page",
                sourceOrigin = "source",
                html = "large-private-html".repeat(180_000),
                verificationEnabled = true,
                verificationKey = "verification-key",
            )
        var ticket: String? = null
        try {
            withContext(Dispatchers.IO) {
                ticket = store.prepare(request)
                val preparedTicket = requireNotNull(ticket)
                assertEquals(request, AppBrowserNavigationStore(context).read(preparedTicket))

                val intent = BrowserNavigation.intent(context, preparedTicket)
                assertEquals(
                    preparedTicket,
                    intent.getStringExtra(BrowserNavigation.PREPARED_TICKET),
                )
                assertFalse(intent.hasExtra("html"))
                assertFalse(intent.hasExtra("url"))
                assertTrue(File(directory, "$preparedTicket.json").isFile)

                store.abandon(preparedTicket)
                assertFalse(File(directory, "$preparedTicket.json").exists())
            }
        } finally {
            withContext(Dispatchers.IO) {
                ticket?.let { id ->
                    listOf("json", "json.bak", "json.new").forEach { suffix ->
                        File(directory, "$id.$suffix").delete()
                    }
                }
            }
        }
    }

    @Test
    fun retryablePreparedReadFailureLeavesTheOriginalPrivatePayloadUntouched() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "browser-navigation")
        val ticket = java.util.UUID.randomUUID().toString()
        val target = File(directory, "$ticket.json")
        val invalidPayload = "{broken payload that must remain for retry}"
        try {
            withContext(Dispatchers.IO) {
                assertTrue(directory.isDirectory || directory.mkdirs())
                target.writeText(invalidPayload)
                repeat(2) {
                    assertTrue(
                        runCatching { AppBrowserNavigationStore(context).read(ticket) }.isFailure
                    )
                    assertEquals(invalidPayload, target.readText())
                }
            }
        } finally {
            withContext(Dispatchers.IO) {
                listOf("json", "json.bak", "json.new").forEach { suffix ->
                    File(directory, "$ticket.$suffix").delete()
                }
            }
        }
    }
}
