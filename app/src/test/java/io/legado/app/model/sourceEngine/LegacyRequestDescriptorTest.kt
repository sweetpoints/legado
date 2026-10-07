package io.legado.app.model.sourceEngine

import io.legado.app.model.analyzeRule.AnalyzeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual OkHttp request bytes from the original URL parser, with no HTTP call. */
class LegacyRequestDescriptorTest {
    private fun resolve(rule: String): Map<String, Any?> =
        AnalyzeUrl(rule, headerMapF = emptyMap(), hasLoginHeader = false)
            .resolveRequestDescriptor(includeCookies = false)

    private fun bytes(value: Map<String, Any?>): String =
        (value["bodyBytes"] as List<*>)
            .map { (it as Number).toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)

    @Test
    fun formPostUsesOriginalEncodingOnceAndKeepsPreencodedFields() {
        val value =
            resolve(
                "https://fixture.invalid/search,{\"method\":\"POST\",\"body\":\"q=hello world&plus=a+b&encoded=%E4%B8%AD\"}"
            )
        assertEquals("POST", value["method"])
        assertEquals("q=hello+world&plus=a%2Bb&encoded=%E4%B8%AD", bytes(value))
        assertEquals("q=hello+world&plus=a%2Bb&encoded=%E4%B8%AD", value["encodedForm"])
        assertTrue((value["contentType"] as String).startsWith("application/x-www-form-urlencoded"))
    }

    @Test
    fun rawMediaJsonAndHeadUseRealRequestBuilder() {
        val raw =
            resolve(
                "https://fixture.invalid/search,{\"method\":\"POST\",\"body\":\"raw+body\",\"headers\":{\"Content-Type\":\"text/plain; charset=utf-8\"}}"
            )
        assertEquals("raw+body", bytes(raw))
        assertEquals("text/plain; charset=utf-8", raw["contentType"])
        val json =
            resolve("https://fixture.invalid/search,{\"method\":\"POST\",\"body\":{\"q\":\"One\"}}")
        assertEquals("{\n  \"q\": \"One\"\n}", bytes(json))
        assertTrue((json["contentType"] as String).startsWith("application/json"))
        val head = resolve("https://fixture.invalid/search?q=space value,{\"method\":\"HEAD\"}")
        assertEquals("HEAD", head["method"])
        assertEquals("https://fixture.invalid/search?q=space%20value", head["url"])
        assertEquals(null, head["bodyBytes"])
    }

    @Test
    fun webViewAndTransportOptionsRemainExplicitAndDoNotStartLoading() {
        val value =
            resolve(
                "https://fixture.invalid/search,{\"webView\":true,\"webViewDelayTime\":321,\"readTimeout\":2,\"callTimeout\":3,\"followRedirects\":false,\"retry\":2}"
            )
        val web = value["webView"] as Map<*, *>
        assertEquals(true, web["enabled"])
        assertEquals(321L, web["delayTime"])
        assertFalse(web["postBeforeLoad"] as Boolean)
        assertEquals(false, value["followRedirects"])
        assertEquals(2, value["retry"])
    }
}
