package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.BuildConfig
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.model.webBook.WebBook
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android HTTP failure through Dart source execution and the real return channel. */
@RunWith(AndroidJUnit4::class)
class LegacyHostFailureV8IntegrationTest {
    @Test
    fun refusedLocalConnectionPreservesNativeFailureCodeAndTypes(): Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue("Mandatory Flutter/V8 backend", BuildConfig.FLUTTER_SOURCE_ENGINE)
            val closedPort = ServerSocket(0).use { it.localPort }
            val base = "http://127.0.0.1:$closedPort"
            val source =
                BookSource(
                    bookSourceUrl = base,
                    bookSourceName = "Closed local HTTP fixture",
                    searchUrl = "$base/closed",
                    ruleSearch = SearchRule(bookList = "tag.a", name = "text", bookUrl = "href"),
                )
            val error = runCatching {
                withTimeout(15_000) { WebBook.searchBookAwait(source, "Local", 1) }
            }
                .exceptionOrNull()
            assertTrue(
                "Closed-port transport must return a typed host error, not a generic or script error",
                error is SourceHostException,
            )
            val hostError = error as SourceHostException
            assertEquals("network_error", hostError.code)
            assertTrue(
                "The actual native ConnectException must survive the RPC round trip",
                "java.net.ConnectException" in hostError.exceptionTypes,
            )
            assertTrue(hostError.exceptionTypes.size in 1..8)
            assertTrue(
                hostError.exceptionTypes.all {
                    it.length <= 256 && Regex("[A-Za-z_$][A-Za-z0-9_.$]*").matches(it)
                }
            )
        }
}
