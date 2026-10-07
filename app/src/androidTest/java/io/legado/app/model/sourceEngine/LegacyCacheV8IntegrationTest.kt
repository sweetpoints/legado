package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Cache
import io.legado.app.help.CacheManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic UUID keys; actual V8/Room/LRU/ACache, with no network or user cache reads. */
@RunWith(AndroidJUnit4::class)
class LegacyCacheV8IntegrationTest {
    private fun source() = BookSource(bookSourceUrl = "https://cache-${UUID.randomUUID()}.invalid", bookSourceName = "Cache fixture")

    @Test fun actualCacheSurvivesVmClearAndSupportsMemoryDiskFileAndJavaBytes() = runBlocking(Dispatchers.IO) {
        val first = source(); val second = source()
        val keys = listOf("text", "memory", "file", "bytes").associateWith { "legacy-cache-${UUID.randomUUID()}" }
        try {
            val result = V8ScriptExecutor.evaluate(
                """
                cache.put(keys.text,'lasting');cache.putMemory(keys.memory,7);cache.putFile(keys.file,'file-fixture');
                cache.put(keys.bytes,java.strToBytes('byte-fixture'));
                ({namespace:typeof cache,getType:typeof cache.get,text:cache.get(keys.text,true),
                  memory:cache.getFromMemory(keys.memory),numeric:cache.getInt(keys.memory),
                  memoryDisk:cache.get(keys.memory,true),file:cache.getFile(keys.file),
                  bytes:java.bytesToStr(cache.getByteArray(keys.bytes)),reflection:typeof cache.getClass})
                """.trimIndent(), bindings = mapOf("keys" to keys), source = first,
            ) as Map<*, *>
            assertEquals("object", result["namespace"]); assertEquals("function", result["getType"])
            assertEquals("lasting", result["text"]); assertEquals(7, (result["memory"] as Number).toInt())
            assertEquals(7, (result["numeric"] as Number).toInt()); assertNull(result["memoryDisk"])
            assertEquals("file-fixture", result["file"]); assertEquals("byte-fixture", result["bytes"])
            assertEquals("undefined", result["reflection"])
            DartSourceEngine.clearSourceState(first)
            assertEquals("lasting", V8ScriptExecutor.evaluate("cache.get(key)", mapOf("key" to keys.getValue("text")), source = second))
            val deleted = V8ScriptExecutor.evaluate(
                "cache.delete(keys.text);cache.delete(keys.bytes);({text:cache.get(keys.text),memory:cache.getFromMemory(keys.text),bytes:cache.getByteArray(keys.bytes)})",
                mapOf("keys" to keys), source = second,
            ) as Map<*, *>
            assertNull(deleted["text"]); assertNull(deleted["memory"]); assertNull(deleted["bytes"])
        } finally {
            keys.values.forEach(CacheManager::delete)
            DartSourceEngine.clearSourceState(first); DartSourceEngine.clearSourceState(second)
        }
    }

    @Test fun ttlUsesSecondsAndExpiredDiskCannotBeHiddenByStaleMemory() = runBlocking(Dispatchers.IO) {
        val source = source(); val key = "legacy-cache-${UUID.randomUUID()}"; val file = "legacy-cache-${UUID.randomUUID()}"
        try {
            V8ScriptExecutor.evaluate("cache.put(key,'old')", mapOf("key" to key), source = source)
            val start = System.currentTimeMillis()
            assertNull(V8ScriptExecutor.evaluate("cache.put(key,'temporary',60);cache.getFromMemory(key)", mapOf("key" to key), source = source))
            val end = System.currentTimeMillis()
            val stored = appDb.cacheDao.get(key)!!
            assertTrue(stored.deadline in (start + 60000)..(end + 60000))
            appDb.cacheDao.insert(Cache(key, "temporary", System.currentTimeMillis() - 1))
            assertNull(V8ScriptExecutor.evaluate("cache.get(key)", mapOf("key" to key), source = source))
            assertNull(V8ScriptExecutor.evaluate("cache.putFile(key,'file-expiry',-1);cache.getFile(key)", mapOf("key" to file), source = source))
        } finally {
            CacheManager.delete(key); CacheManager.delete(file); DartSourceEngine.clearSourceState(source)
        }
    }
}
