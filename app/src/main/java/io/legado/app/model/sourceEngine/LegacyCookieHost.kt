package io.legado.app.model.sourceEngine

import io.legado.app.help.http.CookieStore
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ensureActive

/** The original CookieStore API, with JSON arguments and a registered task context. */
object LegacyCookieHost {
    val methods = setOf("cookieHost.setCookie", "cookieHost.setWebCookie", "cookieHost.replaceCookie",
        "cookieHost.getCookie", "cookieHost.getKey", "cookieHost.removeCookie", "cookieHost.cookieToMap",
        "cookieHost.mapToCookie", "cookieHost.clear")

    interface Backend {
        fun setCookie(url: String, cookie: String?)
        fun setWebCookie(url: String, cookie: String)
        fun replaceCookie(url: String, cookie: String)
        fun getCookie(url: String): String
        fun getKey(url: String, key: String): String
        fun removeCookie(url: String)
        fun cookieToMap(cookie: String): Map<String, String>
        fun mapToCookie(cookie: Map<String, String>?): String?
        fun clear()
    }
    private object Native : Backend {
        override fun setCookie(url: String, cookie: String?) = CookieStore.setCookie(url, cookie)
        override fun setWebCookie(url: String, cookie: String) = CookieStore.setWebCookie(url, cookie)
        override fun replaceCookie(url: String, cookie: String) = CookieStore.replaceCookie(url, cookie)
        override fun getCookie(url: String) = CookieStore.getCookie(url)
        override fun getKey(url: String, key: String) = CookieStore.getKey(url, key)
        override fun removeCookie(url: String) = CookieStore.removeCookie(url)
        override fun cookieToMap(cookie: String) = CookieStore.cookieToMap(cookie)
        override fun mapToCookie(cookie: Map<String, String>?) = CookieStore.mapToCookie(cookie)
        override fun clear() = CookieStore.clear()
    }

    fun call(method: String, args: List<Any?>, context: CoroutineContext = EmptyCoroutineContext,
             backend: Backend = Native): Any? {
        context.ensureActive()
        fun arity(count: Int) = require(args.size == count) { "Invalid legacy cookie overload" }
        fun text(index: Int) = args[index] as? String ?: error("Legacy cookie string argument required")
        return when (method) {
            "cookieHost.setCookie" -> {
                arity(2); require(args[1] == null || args[1] is String)
                backend.setCookie(text(0), args[1] as String?); null
            }
            "cookieHost.setWebCookie" -> { arity(2); backend.setWebCookie(text(0), text(1)); null }
            "cookieHost.replaceCookie" -> { arity(2); backend.replaceCookie(text(0), text(1)); null }
            "cookieHost.getCookie" -> { arity(1); backend.getCookie(text(0)) }
            "cookieHost.getKey" -> { arity(2); backend.getKey(text(0), text(1)) }
            "cookieHost.removeCookie" -> { arity(1); backend.removeCookie(text(0)); null }
            "cookieHost.cookieToMap" -> { arity(1); backend.cookieToMap(text(0)) }
            "cookieHost.mapToCookie" -> {
                arity(1)
                val map = args[0]
                require(map == null || map is Map<*, *>) { "Legacy cookie map required" }
                val values = (map as? Map<*, *>)?.entries?.associate { (key, value) ->
                    require(key is String && value is String) { "Legacy cookie entries must be strings" }
                    key to value
                }
                backend.mapToCookie(values)
            }
            "cookieHost.clear" -> { arity(0); backend.clear(); null }
            else -> error("Unsupported legacy cookie API")
        }
    }
}
