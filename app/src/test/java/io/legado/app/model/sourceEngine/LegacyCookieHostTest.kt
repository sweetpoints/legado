package io.legado.app.model.sourceEngine

import io.legado.app.help.http.CookieStore
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class LegacyCookieHostTest {
    private class Backend : LegacyCookieHost.Backend {
        val calls = mutableListOf<String>()
        var stored: String? = "fixture=before"
        override fun setCookie(url: String, cookie: String?) { calls.add("set"); stored = cookie }
        override fun setWebCookie(url: String, cookie: String) { calls.add("web") }
        override fun replaceCookie(url: String, cookie: String) { calls.add("replace") }
        override fun getCookie(url: String): String { calls.add("get"); return stored.orEmpty() }
        override fun getKey(url: String, key: String): String { calls.add("key"); return "value" }
        override fun removeCookie(url: String) { calls.add("remove"); stored = null }
        override fun cookieToMap(cookie: String) = CookieStore.cookieToMap(cookie)
        override fun mapToCookie(cookie: Map<String, String>?) = CookieStore.mapToCookie(cookie)
        override fun clear() { calls.add("clear") }
    }
    @Test fun realCookieStringMapGoldensPreserveEqualsNullEmptyAndOrder() {
        val actual = LegacyCookieHost.call("cookieHost.cookieToMap", listOf(" first=a=b; empty=; second=null; first=last; ignored"))
        assertEquals(linkedMapOf("first" to "last", "second" to "null"), actual)
        assertEquals("first=last; second=null", LegacyCookieHost.call("cookieHost.mapToCookie", listOf(actual)))
        assertNull(LegacyCookieHost.call("cookieHost.mapToCookie", listOf(null)))
        assertNull(LegacyCookieHost.call("cookieHost.mapToCookie", listOf(emptyMap<String, String>())))
    }
    @Test fun dispatchesOriginalStoreMethodsAndRemovePrecedesSubsequentRead() {
        val backend = Backend()
        fun call(method: String, vararg values: Any?) = LegacyCookieHost.call("cookieHost.$method", values.toList(), backend = backend)
        assertEquals("fixture=before", call("getCookie", "fixture.invalid"))
        call("removeCookie", "fixture.invalid")
        assertEquals("", call("getCookie", "fixture.invalid"))
        call("setCookie", "fixture.invalid", null)
        call("setWebCookie", "fixture.invalid", "x=1")
        call("replaceCookie", "fixture.invalid", "x=2")
        assertEquals("value", call("getKey", "fixture.invalid", "x"))
        call("clear")
        assertEquals(listOf("get", "remove", "get", "set", "web", "replace", "key", "clear"), backend.calls)
    }
    @Test fun unknownMethodsInvalidMapsAndCancellationDoNotTouchStore() {
        val backend = Backend()
        assertThrows(IllegalStateException::class.java) { LegacyCookieHost.call("cookieHost.getClass", emptyList(), backend = backend) }
        assertThrows(IllegalArgumentException::class.java) { LegacyCookieHost.call("cookieHost.mapToCookie", listOf(mapOf("x" to 1)), backend = backend) }
        val job = Job().apply { cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            LegacyCookieHost.call("cookieHost.clear", emptyList(), job, backend)
        }
        assertTrue(backend.calls.isEmpty())
    }
}
