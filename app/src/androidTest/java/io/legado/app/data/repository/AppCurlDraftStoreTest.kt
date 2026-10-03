package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.IntentData
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppCurlDraftStoreTest {
    @Test
    fun actualAtomicDraftRestoresLargeInputOutputAndSelectionWithoutReconsumingIntentData() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val session = UUID.randomUUID().toString()
            val input = "curl https://example.com --data-raw '" + "x".repeat(1_100_000) + "'"
            val token = IntentData.put(input)
            val repo = DefaultCurlConversionRepository(AppCurlDraftStore(context))
            try {
                val first = repo.restore(session, token)
                assertEquals(input, first.input)
                val draft =
                    first.copy(
                        output = "output " + "y".repeat(1_100_000),
                        selectionStart = 20,
                        selectionEnd = 60,
                        revision = 1,
                    )
                repo.save(session, draft)
                val restored =
                    DefaultCurlConversionRepository(AppCurlDraftStore(context))
                        .restore(session, token)
                assertEquals(draft, restored)
                assertNull(IntentData.get<String>(token))
            } finally {
                File(context.filesDir, "curl-converter-drafts/$session.json").delete()
            }
        }

    @Test
    fun independentStoreInstancesRejectLateOlderRevisionsUnderConcurrentWrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = UUID.randomUUID().toString()
        val first = AppCurlDraftStore(context)
        val second = AppCurlDraftStore(context)
        try {
            first.write(session, CurlConversionDraft(input = "first", revision = 1))
            second.write(
                session,
                CurlConversionDraft(input = "latest", output = "output", revision = 10),
            )
            first.write(session, CurlConversionDraft(input = "late old editor", revision = 2))
            assertEquals("latest", second.read(session)!!.input)
            kotlinx.coroutines.coroutineScope {
                (11L..30L)
                    .map { revision ->
                        launch(kotlinx.coroutines.Dispatchers.IO) {
                            (if (revision % 2L == 0L) first else second).write(
                                session,
                                CurlConversionDraft(
                                    input = "revision-$revision",
                                    revision = revision,
                                ),
                            )
                        }
                    }
                    .forEach { it.join() }
            }
            assertEquals(30L, first.read(session)!!.revision)
            assertEquals("revision-30", second.read(session)!!.input)
        } finally {
            File(context.filesDir, "curl-converter-drafts/$session.json").delete()
        }
    }
}
