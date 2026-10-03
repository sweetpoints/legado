package io.legado.app.ui.code

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeEditorFormatterInputTest {
    private class Cache : CodeEditorFormatterInputCache {
        val entries = ConcurrentHashMap<String, String>()

        override fun put(key: String, text: String) {
            entries[key] = text
        }

        override fun remove(key: String) {
            entries.remove(key)
        }
    }

    @Test
    fun overlappingWorkersReadTheirOwnInputAndCleanupLeavesTheOtherOwner() = runBlocking {
        val cache = Cache()
        val firstReady = CompletableDeferred<String>()
        val secondReady = CompletableDeferred<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val first =
            async(Dispatchers.Default) {
                withCodeEditorFormatterInput("function first(){}", cache) { key ->
                    firstReady.complete(key)
                    releaseFirst.await()
                    cache.entries[key]
                }
            }
        val firstKey = firstReady.await()
        val second =
            async(Dispatchers.Default) {
                withCodeEditorFormatterInput("function second(){}", cache) { key ->
                    secondReady.complete(key)
                    releaseSecond.await()
                    cache.entries[key]
                }
            }
        val secondKey = secondReady.await()
        assertNotEquals(firstKey, secondKey)
        releaseFirst.complete(Unit)
        assertEquals("function first(){}", first.await())
        assertNull(cache.entries[firstKey])
        assertEquals("function second(){}", cache.entries[secondKey])
        releaseSecond.complete(Unit)
        assertEquals("function second(){}", second.await())
        assertTrue(cache.entries.isEmpty())
    }

    @Test
    fun cancellationClearsOnlyTheCancelledInput() = runBlocking {
        val cache = Cache().apply { entries["unrelated"] = "keep" }
        val ready = CompletableDeferred<String>()
        val worker =
            launch(Dispatchers.Default) {
                withCodeEditorFormatterInput("raw", cache) { key ->
                    ready.complete(key)
                    awaitCancellation()
                }
            }
        val key = ready.await()
        worker.cancelAndJoin()
        assertNull(cache.entries[key])
        assertEquals(mapOf("unrelated" to "keep"), cache.entries.toMap())
    }

    @Test
    fun formatterFailureClearsItsInputWithoutMaskingTheOriginalError() = runBlocking {
        val cache = Cache().apply { entries["unrelated"] = "keep" }
        var failed = false
        try {
            withCodeEditorFormatterInput("raw", cache) { throw IOException("format failed") }
        } catch (error: IOException) {
            failed = true
            assertEquals("format failed", error.message)
        }
        assertTrue(failed)
        assertEquals(mapOf("unrelated" to "keep"), cache.entries.toMap())
    }
}
