package io.legado.app.model.jsSource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class JsSourceEngineTest {
    @Test
    fun normalizesDecodedJsonMapsAndLists() {
        val result = listOf(mapOf("name" to "测试", "bookUrl" to "u2"))
        val json = JsSourceEngine.normalizeJsResult(result).orEmpty()
        assertTrue(json.contains("测试"))
        assertTrue(json.contains("u2"))
    }

    @Test
    fun cancelledNormalizationPreservesCancellation() {
        val job = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            JsSourceEngine.normalizeJsResult(mapOf("ok" to true), job)
        }
    }

    @Test
    fun contentStringPassesThroughUnchanged() {
        assertEquals("第一段\n第二段", JsSourceEngine.normalizeJsResult("第一段\n第二段"))
    }

    @Test
    fun decodedNullRemainsNull() {
        assertNull(JsSourceEngine.normalizeJsResult(null))
    }
}
