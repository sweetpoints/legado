package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.browser.BrowserRequest
import io.legado.app.ui.browser.BrowserNavigation
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBrowserNavigationStoreTest {
    @Test
    fun canceledReturnHopRemovesOnlyItsOwnUnhandedOffTicket() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AppBrowserNavigationStore(context)
        val neighbor = store.prepare(BrowserRequest("https://neighbor.invalid"))
        val directory = File(context.filesDir, "browser-navigation")
        val dispatcher = HoldingDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
        var created: File? = null
        val job = scope.launch {
            BrowserNavigation.prepare(
                context,
                BrowserRequest("https://canceled.invalid", html = "large".repeat(50_000)),
            )
        }
        try {
            dispatcher.take().run()
            created = awaitNewNavigationFile(directory, neighbor)
            val returnHop = dispatcher.take()
            job.cancel()
            returnHop.run()
            withTimeoutCompletion(job, dispatcher)

            assertFalse(requireNotNull(created).exists())
            assertEquals("https://neighbor.invalid", store.read(neighbor).url)
        } finally {
            job.cancel()
            scope.cancel()
            runCatching { store.abandon(neighbor) }
            created?.name?.removeSuffix(".json")?.let { ticket ->
                listOf("json", "json.bak", "json.new").forEach { suffix ->
                    File(directory, "$ticket.$suffix").delete()
                }
            }
        }
    }

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

    private suspend fun awaitNewNavigationFile(directory: File, neighbor: String): File {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val created =
                directory.listFiles()?.firstOrNull {
                    it.name.endsWith(".json") && it.name != "$neighbor.json"
                }
            if (created != null) return created
            kotlinx.coroutines.delay(10)
        }
        error("Prepared browser ticket was not written")
    }

    private suspend fun withTimeoutCompletion(
        job: kotlinx.coroutines.Job,
        dispatcher: HoldingDispatcher,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!job.isCompleted && System.nanoTime() < deadline) {
            dispatcher.poll()?.run() ?: kotlinx.coroutines.delay(10)
        }
        assertTrue("Canceled prepare did not complete", job.isCompleted)
    }

    private class HoldingDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.put(block)
        }

        fun take(): Runnable =
            tasks.poll(10, TimeUnit.SECONDS) ?: error("Timed out waiting for coroutine")

        fun poll(): Runnable? = tasks.poll()
    }
}
