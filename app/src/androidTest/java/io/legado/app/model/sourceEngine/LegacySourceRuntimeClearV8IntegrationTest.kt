package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.BookSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual main and auxiliary V8 contexts with no org API, HTTP or user data. */
@RunWith(AndroidJUnit4::class)
class LegacySourceRuntimeClearV8IntegrationTest {
    private fun source(id: String) =
        BookSource(
            bookSourceUrl = id,
            bookSourceName = "Main clear fixture",
            mainJs =
                "globalThis.retainedCounter ??= 0;function search(key,page){retainedCounter++;return [{name:'Fixture',bookUrl:'https://clear.invalid/book',counter:retainedCounter}];}",
        )

    private suspend fun count(source: BookSource): Int =
        withTimeout(20_000) {
            (DartSourceEngine.execute(source, "search", mapOf("key" to "fixture", "page" to 1))
                    .single()["counter"]
                    as Number)
                .toInt()
        }

    private suspend fun auxiliary(source: BookSource, script: String): Any? =
        withTimeout(20_000) {
            V8ScriptExecutor.evaluate(script, source = source)
        }

    private suspend fun verifyClear(rawEngineId: Boolean) {
        val id = "https://clear-${UUID.randomUUID()}.invalid/source"
        val first = source(id)
        val neighbor = source(id + "-neighbor")
        try {
            assertEquals(1, count(first))
            assertEquals(2, count(first))
            assertEquals(1, count(neighbor))
            assertEquals(
                "first",
                auxiliary(first, "globalThis.__runtimeClearMarker='first';__runtimeClearMarker"),
            )
            assertEquals(
                "neighbor",
                auxiliary(
                    neighbor,
                    "globalThis.__runtimeClearMarker='neighbor';__runtimeClearMarker",
                ),
            )
            if (rawEngineId) DartSourceEngine.clearSourceState(first.bookSourceUrl)
            else DartSourceEngine.clearSourceState(first)
            assertEquals(
                "The main search VM must be recreated even without any org API",
                1,
                count(first),
            )
            assertEquals(
                "The auxiliary VM must also lose its saved global",
                "undefined",
                auxiliary(first, "typeof __runtimeClearMarker"),
            )
            assertEquals(
                "A similar source ID prefix must keep its main context",
                2,
                count(neighbor),
            )
            assertEquals("neighbor", auxiliary(neighbor, "__runtimeClearMarker"))
        } finally {
            DartSourceEngine.clearSourceState(first)
            DartSourceEngine.clearSourceState(neighbor)
        }
    }

    @Test
    fun canonicalClearResetsMainAndAuxiliaryContextsWithoutClearingPrefixNeighbor(): Unit {
        runBlocking(Dispatchers.IO) { verifyClear(rawEngineId = false) }
    }

    @Test
    fun rawEngineIdClearResetsMainAndAuxiliaryContextsWithoutClearingPrefixNeighbor(): Unit {
        runBlocking(Dispatchers.IO) { verifyClear(rawEngineId = true) }
    }
}
