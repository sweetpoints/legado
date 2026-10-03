package io.legado.app.data.entities

import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTtsCookieJarTest {

    @Test
    fun `auxiliary fields survive import export and editing`() {
        val jsLib = "function sign(text) { return java.md5Encode(text) }"
        val imported =
            HttpTTS.fromJson(
                    """{"name":"test","url":"https://example.com","jsLib":"$jsLib","enabledCookieJar":true}"""
                )
                .getOrThrow()
        val legacy =
            HttpTTS.fromJson("""{"name":"test","url":"https://example.com"}""").getOrThrow()
        val roundTrip = HttpTTS.fromJson(GSON.toJson(imported)).getOrThrow()

        assertEquals(jsLib, imported.jsLib)
        assertTrue(imported.enabledCookieJar == true)
        assertNull(legacy.jsLib)
        assertEquals(false, legacy.enabledCookieJar)
        assertEquals(jsLib, roundTrip.jsLib)
        assertTrue(roundTrip.enabledCookieJar == true)
    }
}
