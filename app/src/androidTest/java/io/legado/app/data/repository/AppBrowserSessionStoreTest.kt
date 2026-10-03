package io.legado.app.data.repository

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.browser.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AppBrowserSessionStoreTest {
    @Test
    fun actualAtomicSessionRestoresLargeHtmlMetadataAndBackupRejectsOldWritersThenReleasesOnlyOwnedArtifacts() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val session = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val oldOwner = UUID.randomUUID().toString()
            val owner = UUID.randomUUID().toString()
            val otherOwner = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "browser-sessions")
            val first = AppBrowserSessionStore(context)
            val recreated = AppBrowserSessionStore(context)
            try {
                withContext(Dispatchers.IO) {
                    val request =
                        BrowserRequest(
                            "request",
                            html = "x".repeat(1200000),
                            verificationKey = "key",
                        )
                    val page =
                        BrowserPage(
                            request,
                            "resolved",
                            request.html,
                            true,
                            mapOf("X-Custom" to "unchanged"),
                            "UA",
                            BrowserSource(1, "metadata"),
                        )
                    val original =
                        BrowserSession(
                            request,
                            page,
                            image = "data:image/large",
                            receipt =
                                BrowserReceipt(
                                    "receipt",
                                    BrowserReceiptKind.Verified,
                                    BrowserVerification("full result", "current"),
                                ),
                            revision = 100,
                        )
                    first.claim(session, oldOwner)
                    first.create(session, oldOwner, original)
                    recreated.claim(session, owner)
                    first.release(session, oldOwner)
                    assertFalse(first.write(session, oldOwner, original.copy(revision = 101)))
                    assertEquals(original, recreated.read(session, owner))
                    recreated.claim(other, otherOwner)
                    first.create(other, otherOwner, original.copy(title = "other"))
                    assertFalse(
                        recreated.write(session, owner, original.copy(revision = 99, page = null))
                    )
                    assertTrue(recreated.write(session, owner, original))
                    assertFalse(
                        recreated.write(
                            session,
                            owner,
                            original.copy(title = "different same revision"),
                        )
                    )
                    assertEquals(original, recreated.read(session, owner))
                    coroutineScope {
                        (101L..120L)
                            .map { revision ->
                                async {
                                    recreated.write(
                                        session,
                                        owner,
                                        original.copy(revision = revision),
                                    )
                                }
                            }
                            .awaitAll()
                    }
                    assertEquals(120L, first.read(session, owner)!!.revision)
                    val file = File(directory, "$session.json")
                    assertTrue(file.renameTo(File(file.path + ".bak")))
                    assertEquals(1200000, recreated.read(session, owner)!!.page!!.html!!.length)
                    assertEquals("key", recreated.read(session, owner)!!.request.verificationKey)
                    File(file.path + ".new").writeText("leftover large partial body")
                    recreated.release(session, owner)
                    listOf("", ".bak", ".new").forEach {
                        assertFalse(File(file.path + it).exists())
                    }
                    listOf("owner", "owner.bak", "owner.new").forEach {
                        assertFalse(File(directory, "$session.$it").exists())
                    }
                    assertEquals("other", first.read(other, otherOwner)!!.title)
                    try {
                        first.write(session, owner, original.copy(revision = Long.MAX_VALUE))
                        fail("closed writer")
                    } catch (_: BrowserSessionClosedException) {}
                    try {
                        first.create(session, owner, original)
                        fail("closed owner")
                    } catch (_: BrowserSessionClosedException) {}
                    try {
                        first.read(session, owner)
                        fail("closed reader")
                    } catch (_: BrowserSessionClosedException) {}
                    first.release(session, owner)
                    first.release(other, otherOwner)
                }
            } finally {
                withContext(Dispatchers.IO) {
                    listOf(session, other).forEach { key ->
                        listOf(
                                "json",
                                "json.bak",
                                "json.new",
                                "closed",
                                "closed.bak",
                                "closed.new",
                                "owner",
                                "owner.bak",
                                "owner.new",
                            )
                            .forEach { File(directory, "$key.$it").delete() }
                    }
                }
            }
        }

    @Test
    fun actualLocalHtmlPreparationAndCapturedJavascriptPreserveRawHtmlAndImageBytesOnIo() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "browser-image-${UUID.randomUUID()}")
            directory.mkdirs()
            val store = AppBrowserDataStore()
            try {
                withContext(Dispatchers.IO) {
                    val html = "<html><HEAD><title>Original</title></HEAD><body>完整内容</body></html>"
                    val request = BrowserRequest("https://browser-fixture.invalid", html = html)
                    val page = store.prepare(request)
                    assertTrue(page.localHtml)
                    assertTrue(page.html!!.contains("完整内容"))
                    assertTrue(page.html!!.contains("<HEAD><script>"))
                    assertEquals(
                        BrowserVerification(page.html!!, page.baseUrl),
                        store.refetch(page),
                    )
                    assertEquals(
                        BrowserVerification("<html>\"quoted\"\n中文</html>", "navigated"),
                        store.captured("\"<html>\\\"quoted\\\"\\n中文</html>\"", "navigated"),
                    )
                    val bytes = byteArrayOf(1, 2, 3, -1, 12)
                    store.saveImage(
                        "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                        directory.path,
                    )
                    assertArrayEquals(bytes, directory.listFiles()!!.single().readBytes())
                }
            } finally {
                withContext(Dispatchers.IO) {
                    directory.listFiles()?.forEach { it.delete() }
                    directory.delete()
                }
            }
        }
}
