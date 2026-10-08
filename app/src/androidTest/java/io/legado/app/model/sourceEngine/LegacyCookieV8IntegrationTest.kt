package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieStore
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual V8 CookieStore RPC and native jar; only unique local fixture cookies. */
@RunWith(AndroidJUnit4::class)
class LegacyCookieV8IntegrationTest {
    @Test fun legacyRemoveSourceCookieUpdatesNativeJarBeforeNextGet() = runBlocking(Dispatchers.IO) {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val requests = CompletableFuture<List<String>>()
        val worker = thread(isDaemon = true, name = "Legacy-cookie-fixture") {
            try {
                val cookies = mutableListOf<String>()
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        var cookie = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Cookie:", ignoreCase = true)) cookie = line.substringAfter(':').trim()
                        }
                        cookies.add(cookie)
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
                requests.complete(cookies)
            } catch (error: Exception) { requests.completeExceptionally(error) }
        }
        val url = "http://legacy-cookie-${UUID.randomUUID()}.invalid:${server.localPort}/fixture"
        val source = BookSource(bookSourceUrl = url, bookSourceName = "Cookie fixture")
        val client = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .addInterceptor { chain -> chain.proceed(CookieManager.loadRequest(chain.request())) }
            .build()
        try {
            CookieStore.setCookie(url.toString(), "legacy-fixture=before")
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) }
            assertEquals("", V8ScriptExecutor.evaluate(
                "cookie.removeCookie(source.getKey()); cookie.getCookie(source.getKey())", source = source,
            ))
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) }
            val seen = requests.get(5, TimeUnit.SECONDS)
            assertEquals("legacy-fixture=before", seen[0])
            assertEquals("", seen[1])
        } finally {
            CookieStore.removeCookie(url.toString())
            DartSourceEngine.clearSourceState(source)
            server.close()
            worker.join(1000)
        }
    }

    @Test fun legacyCookieMapConversionCrossesActualV8JsonBoundary() = runBlocking(Dispatchers.IO) {
        val source = BookSource(bookSourceUrl = "https://cookie-${UUID.randomUUID()}.invalid", bookSourceName = "Cookie map fixture")
        try {
            val result = V8ScriptExecutor.evaluate(
                "({cookie:cookie.mapToCookie(cookie.cookieToMap('a=1; b=two=parts')),empty:cookie.mapToCookie({}),reflection:typeof cookie.getClass})",
                source = source,
            ) as Map<*, *>
            assertEquals("a=1; b=two=parts", result["cookie"])
            assertNull(result["empty"])
            assertEquals("undefined", result["reflection"])
        } finally { DartSourceEngine.clearSourceState(source) }
    }
}
