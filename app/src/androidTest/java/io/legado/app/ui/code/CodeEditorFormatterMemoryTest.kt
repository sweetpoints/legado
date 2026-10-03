package io.legado.app.ui.code

import io.legado.app.help.CacheManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeEditorFormatterMemoryTest {
    @Test
    fun realCacheKeepsOverlappingFormatterPayloadsUntilTheirOwnFinally() = runBlocking {
        val firstReady = CompletableDeferred<String>()
        val secondReady = CompletableDeferred<String>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val first =
            async(Dispatchers.Default) {
                withCodeEditorFormatterInput("😀\r\nfirst") { key ->
                    firstReady.complete(key)
                    firstRelease.await()
                    CacheManager.getFromMemory(key)
                }
            }
        val firstKey = firstReady.await()
        val second =
            async(Dispatchers.Default) {
                withCodeEditorFormatterInput("second") { key ->
                    secondReady.complete(key)
                    secondRelease.await()
                    CacheManager.getFromMemory(key)
                }
            }
        val secondKey = secondReady.await()
        assertNotEquals(firstKey, secondKey)
        firstRelease.complete(Unit)
        assertEquals("😀\r\nfirst", first.await())
        assertNull(CacheManager.getFromMemory(firstKey))
        assertEquals("second", CacheManager.getFromMemory(secondKey))
        secondRelease.complete(Unit)
        assertEquals("second", second.await())
        assertNull(CacheManager.getFromMemory(secondKey))
    }
}
