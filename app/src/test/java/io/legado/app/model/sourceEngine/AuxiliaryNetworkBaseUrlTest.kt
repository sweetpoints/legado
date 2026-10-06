package io.legado.app.model.sourceEngine

import org.junit.Assert.assertEquals
import org.junit.Test

class AuxiliaryNetworkBaseUrlTest {
    @Test
    fun blankAndNonHttpScriptBindingsDoNotBecomeNetworkOrigins() {
        for (value in listOf(null, "", " ", "httpTts:9", "file:///book", "relative/page", 0)) {
            assertEquals("https://script.legado.invalid/", auxiliaryNetworkBaseUrl(value))
        }
    }

    @Test
    fun explicitHttpOriginsRetainTheirPagePathAndQuery() {
        for (value in listOf("https://example.com/book/one?page=2", "HTTP://example.com/page")) {
            assertEquals(value, auxiliaryNetworkBaseUrl(value))
        }
    }
}
