package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.CacheManager
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class RssArticleImageRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "rss-image-fixture-${UUID.randomUUID()}").apply { mkdirs() }
    @After fun cleanup() { directory.deleteRecursively() }
    private class Ratios : RssArticleRatioStore {
        val values = mutableMapOf<String, Float>()
        override fun get(source: String) = values[source]
        override fun put(source: String, ratio: Float) { values[source] = ratio }
    }
    private fun png(): ByteArray = Bitmap.createBitmap(24, 48, Bitmap.Config.ARGB_8888).let { bitmap ->
        try { bitmap.eraseColor(android.graphics.Color.RED); ByteArrayOutputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() } }
        finally { bitmap.recycle() }
    }
    @Test fun actualImageLoaderRetainsSourceAuthenticationAndNaturalRatioCache() = runBlocking {
        FixtureServer(png()).use { server ->
            val source = RssSource(sourceUrl = "${server.url}/source-${UUID.randomUUID()}", header = """{"X-Rss-Fixture":"owned-token"}""")
            withContext(Dispatchers.IO) { appDb.rssSourceDao.insert(source) }
            val ratios = Ratios(); val repository = GlideRssArticleImageRepository(context, ratios)
            val url = "${server.url}/image-${UUID.randomUUID()}.png"
            try {
                val result = withTimeout(10000) { repository.load(url, source.sourceUrl, 24, 0, true) }!!
                try {
                    assertEquals("owned-token", server.header.get()); assertEquals(2f, ratios.values[url]!!, 0f)
                    assertEquals(2f, repository.ratio(url)!!, 0f); assertFalse(result.isReleased)
                } finally { result.release() }
                assertTrue(result.isReleased); assertNull(result.drawable.callback)
            } finally { withContext(Dispatchers.IO) { appDb.rssSourceDao.delete(source) } }
        }
    }
    @Test fun gifResourceRetainsAnimationUntilCallerReleasesLease() = runBlocking {
        val bytes = hex("47494638396101000100800000000000ffffff21ff0b4e45545343415045322e30030100000021f904000a0000002c000000000100010000020244010021f904000a0000002c00000000010001000002024c01003b")
        val file = File(directory, "animated.gif").apply { writeBytes(bytes) }
        val repository = GlideRssArticleImageRepository(context, Ratios())
        val result = withTimeout(10000) { repository.load(file.absolutePath, "fixture", 24, 24, false) }!!
        try { assertTrue(result.drawable is Animatable); assertFalse(result.isReleased) }
        finally { result.release() }
        result.release(); assertTrue(result.isReleased); assertNull(result.drawable.callback)
    }
    @Test fun invalidImageReturnsNoLeaseAndPersistedAspectStoreAcceptsOnlyPositiveFiniteValues() = runBlocking {
        val bad = File(directory, "bad.png").apply { writeText("not an image") }
        assertNull(withTimeout(10000) { GlideRssArticleImageRepository(context, Ratios()).load(bad.absolutePath, "fixture", 24, 24, false) })
        val store = CacheRssArticleRatioStore(); val key = "fixture-${UUID.randomUUID()}"
        try {
            withContext(Dispatchers.IO) { store.put(key, Float.NaN); store.put(key, -1f); assertNull(store.get(key)); store.put(key, 1.5f) }
            assertEquals(1.5f, withContext(Dispatchers.IO) { CacheRssArticleRatioStore().get(key)!! }, 0f)
        } finally { withContext(Dispatchers.IO) { CacheManager.delete("img_ar_$key") } }
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private class FixtureServer(private val bytes: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"; val header = AtomicReference<String?>()
        private val worker = Thread {
            try { while (!server.isClosed) server.accept().use { socket ->
                socket.soTimeout = 5000; val input = socket.getInputStream().bufferedReader(); input.readLine()
                var line = input.readLine()
                while (!line.isNullOrEmpty()) { if (line.startsWith("X-Rss-Fixture:", true)) header.set(line.substringAfter(':').trim()); line = input.readLine() }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            } } catch (error: SocketException) { if (!server.isClosed) throw error }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); worker.join(5000); check(!worker.isAlive) }
    }
}
