package io.legado.app.help.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.utils.GSON
import io.legado.app.utils.InfoMap
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real discovery entry point and V8, without external network dependencies. */
@RunWith(AndroidJUnit4::class)
class ExploreScriptV8IntegrationTest {
    @Test
    fun oldExportedMenusExecuteThroughActualV8WithWhitespaceAfterClosingTag(): Unit =
        runBlocking(Dispatchers.IO) {
            withTimeout(30_000) {
                for (suffix in listOf("", "\n", " \t\r\n", "\\n", " // menu")) {
                    val source =
                        BookSource(
                            bookSourceUrl = "https://explore-${UUID.randomUUID()}.invalid",
                            bookSourceName = "Discovery fixture",
                            exploreUrl =
                                "<js>JSON.stringify([{title:'Fixture',url:'https://fixture.invalid/list'}])</js>$suffix",
                        )
                    try {
                        val kinds = source.exploreKinds()
                        assertEquals(1, kinds.size)
                        assertEquals("Fixture", kinds.single().title)
                        assertEquals("https://fixture.invalid/list", kinds.single().url)
                    } finally {
                        source.clearExploreKindsCache()
                        DartSourceEngine.clearSourceState(source)
                    }
                }
            }
        }
    @Test
    fun infoMapMethodsAndPropertiesMutateLiveMapWithoutImplicitPersistence(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000) {
            val source = BookSource(bookSourceUrl = "https://info-${UUID.randomUUID()}.invalid", bookSourceName = "InfoMap fixture")
            val key = "infoMap_${source.bookSourceUrl}"
            val info = InfoMap(source.bookSourceUrl)
            try {
                val result = evaluateExploreScript(source, """
                    infoMap.direct = 'first';
                    const live = infoMap.get();
                    live.other = 'second';
                    const old = infoMap.put('direct', 'updated');
                    const removed = infoMap.remove('other');
                    infoMap.putAll({third:'third'});
                    ({old, removed, direct:live.direct, size:infoMap.size(),
                            empty:infoMap.isEmpty(), has:infoMap.containsKey('third'),
                            value:infoMap.containsValue('updated'), missing:infoMap.get('missing'),
                            source:infoMap.sourceUrl, snapshot:JSON.stringify(infoMap)});
                """.trimIndent(), info) as Map<*, *>
                assertEquals("first", result["old"])
                assertEquals("second", result["removed"])
                assertEquals("updated", result["direct"])
                assertEquals(2, (result["size"] as Number).toInt())
                assertEquals(false, result["empty"])
                assertEquals(true, result["has"])
                assertEquals(true, result["value"])
                assertNull(result["missing"])
                assertEquals(source.bookSourceUrl, result["source"])
                assertJsonEquals(GSON.toJson(info.get()), result["snapshot"])
                assertEquals(mapOf("direct" to "updated", "third" to "third"), info.get())
                assertFalse(info.needSave)
                assertNull(CacheManager.get(key))
                evaluateExploreScript(source, "infoMap.set({replacement:'yes'}); infoMap.get().gone='x'; delete infoMap.gone; infoMap.save();", info)
                assertEquals(mapOf("replacement" to "yes"), info.get())
                assertTrue(info.needSave)
                assertNull(CacheManager.get(key))
                info.saveNow() // Existing ExploreHomeRepository flush policy.
                assertFalse(info.needSave)
                assertJsonEquals(GSON.toJson(info.get()), CacheManager.get(key))
                evaluateExploreScript(source, "infoMap.clear(); infoMap.isEmpty();", info).also {
                    assertEquals(true, it)
                }
            } finally {
                CacheManager.delete(key)
                DartSourceEngine.clearSourceState(source)
            }
        }
    }

    @Test
    fun infoMapSaveNowRunsAtCallTimeAndRetainsTtlEvenWhenNeedIsFalse(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000) {
            val source = BookSource(bookSourceUrl = "https://info-ttl-${UUID.randomUUID()}.invalid", bookSourceName = "InfoMap TTL fixture")
            val key = "infoMap_${source.bookSourceUrl}"
            val info = InfoMap(source.bookSourceUrl)
            try {
                val before = System.currentTimeMillis()
                val result = evaluateExploreScript(source, """
                    infoMap.put('value', 'saved');
                    infoMap.save(60, false);
                    const pending = infoMap.needSave;
                    const savedVoid = infoMap.saveNow() === undefined;
                    infoMap.value = 'after-save';
                    ({pending, need:infoMap.getNeedSave(), savedVoid});
                """.trimIndent(), info) as Map<*, *>
                assertEquals(false, result["pending"])
                assertEquals(true, result["savedVoid"])
                assertEquals(false, result["need"])
                assertFalse(info.needSave)
                assertEquals("after-save", info["value"])
                assertJsonEquals("{\"value\":\"saved\"}", CacheManager.get(key))
                val deadline = appDb.cacheDao.get(key)!!.deadline
                assertTrue(deadline >= before + 60_000 && deadline <= System.currentTimeMillis() + 60_000)
                evaluateExploreScript(source, "infoMap.needSave=true; infoMap.setNeedSave(false);", info)
                assertFalse(info.needSave)
                assertJsonEquals("{\"value\":\"saved\"}", CacheManager.get(key))
                evaluateExploreScript(source, "infoMap.save(-1, false); infoMap.saveNow();", info)
                assertNull(CacheManager.get(key))
            } finally {
                CacheManager.delete(key)
                DartSourceEngine.clearSourceState(source)
            }
        }
    }
    private fun assertJsonEquals(expected: String, actual: Any?) {
        assertTrue("Expected a JSON string", actual is String)
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual as String))
    }

}
