package io.legado.app.data.repository

import io.legado.app.model.analyzeRule.CurlAnalyzeUrlConverter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CurlConversionRepositoryTest {
    @Test
    fun restoreDetectsInitialDirectionAndOnlyConsumesInputOnceAndCachesFullDraft() = runBlocking {
        val store = Fake("https://example.com,{\"method\":\"HEAD\"}")
        val repo = DefaultCurlConversionRepository(store)
        val draft = repo.restore("session", "seed")
        assertEquals(CurlDirection.AnalyzeToCurl, draft.direction)
        assertEquals(draft.input.length, draft.selectionStart)
        val saved =
            draft.copy(output = "curl command", selectionStart = 2, selectionEnd = 9, revision = 1)
        repo.save("session", saved)
        assertEquals(saved, repo.restore("session", "seed"))
        assertEquals(1, store.seeds)
    }

    @Test
    fun blankAndCurlInputsKeepCurlToAnalyzeDirection() = runBlocking {
        listOf("", "curl https://example.com", " CURL.EXE -I https://example.com").forEach { input
            ->
            assertEquals(
                CurlDirection.CurlToAnalyze,
                DefaultCurlConversionRepository(Fake(input)).restore("session", "seed").direction,
            )
        }
    }

    @Test
    fun productionConverterPreservesUrlMethodHeadersCookiesAndLiteralBody() = runBlocking {
        val repo = DefaultCurlConversionRepository(Fake())
        val converted =
            repo.convert(
                "curl https://example.com/api -H 'Accept: application/json' --cookie 'sid=a=b' --data-raw '{\"name\":\"reader\"}'",
                CurlDirection.CurlToAnalyze,
            )
        val options =
            GSON.fromJsonObject<Map<String, Any>>(converted.substringAfter(',')).getOrThrow()
        assertEquals("POST", options["method"])
        assertEquals("{\"name\":\"reader\"}", options["body"])
        assertEquals("application/json", (options["headers"] as Map<*, *>)["Accept"])
        assertEquals("sid=a=b", (options["headers"] as Map<*, *>)["Cookie"])
        val curl = repo.convert(converted, CurlDirection.AnalyzeToCurl)
        assertTrue(curl.startsWith("curl -g "))
        assertTrue(curl.contains("sid=a=b"))
        assertTrue(curl.contains("--data-raw"))
    }

    @Test
    fun conversionFailureKeepsExactReasonAndDetailForLocalizedUi() = runBlocking {
        val repo = DefaultCurlConversionRepository(Fake())
        try {
            repo.convert("curl -X DELETE https://example.com", CurlDirection.CurlToAnalyze)
            fail("expected failure")
        } catch (error: CurlAnalyzeUrlConverter.ConversionException) {
            assertEquals(CurlAnalyzeUrlConverter.ErrorReason.UNSUPPORTED_METHOD, error.reason)
            assertEquals("DELETE", error.detail)
        }
    }

    @Test
    fun storeReadWriteAndSeedAlwaysRunOnInjectedIoThreadAndFailureDoesNotOverwriteExistingDraft() =
        runBlocking {
            Executors.newSingleThreadExecutor { Thread(it, "curl-store-io") }
                .asCoroutineDispatcher()
                .use { dispatcher ->
                    val store = Fake("curl https://example.com")
                    val repo = DefaultCurlConversionRepository(store, dispatcher)
                    val initial = repo.restore("session", "seed")
                    repo.save("session", initial.copy(output = "output", revision = 1))
                    assertTrue(store.threads.isNotEmpty())
                    assertTrue(store.threads.all { it === store.threads.first() })
                    assertFalse(store.threads.first() === Thread.currentThread())
                    val saved = store.draft
                    store.failRead = true
                    try {
                        repo.restore("session", "seed")
                        fail("expected read error")
                    } catch (error: IllegalStateException) {
                        assertEquals("read", error.message)
                    }
                    assertEquals(saved, store.draft)
                    assertEquals(1, store.seeds)
                }
        }

    @Test
    fun failedInitialWriteKeepsConsumedSeedForRetryUntilItBecomesDurable() = runBlocking {
        val store = Fake("https://example.com,{\"method\":\"HEAD\"}").apply { writeFailures = 1 }
        val repo = DefaultCurlConversionRepository(store)
        try {
            repo.restore("session", "seed")
            fail("expected write failure")
        } catch (error: IllegalStateException) {
            assertEquals("write", error.message)
        }
        assertNull(store.draft)
        assertEquals(1, store.seeds)
        val restored = repo.restore("session", "seed")
        assertEquals("https://example.com,{\"method\":\"HEAD\"}", restored.input)
        assertEquals(CurlDirection.AnalyzeToCurl, restored.direction)
        assertEquals(1, store.seeds)
        assertEquals(restored, repo.restore("session", "seed"))
        assertEquals(1, store.seeds)
    }

    private class Fake(private val seed: String = "") : CurlDraftStore {
        var draft: CurlConversionDraft? = null
        var seeds = 0
        var failRead = false
        var writeFailures = 0
        val threads = mutableListOf<Thread>()

        override suspend fun read(session: String): CurlConversionDraft? {
            threads += Thread.currentThread()
            if (failRead) error("read")
            return draft
        }

        override suspend fun write(session: String, draft: CurlConversionDraft) {
            threads += Thread.currentThread()
            if (writeFailures > 0) {
                writeFailures--
                error("write")
            }
            this.draft = draft
        }

        override suspend fun initial(inputKey: String?): String {
            threads += Thread.currentThread()
            seeds++
            return seed
        }
    }
}
