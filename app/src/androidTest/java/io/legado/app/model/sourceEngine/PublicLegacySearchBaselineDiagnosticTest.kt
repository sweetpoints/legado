package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.legado.app.BuildConfig
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.source.SuppressSourceNavigation
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic projection of fixed public source 1; never a live four-source acceptance claim. */
@RunWith(AndroidJUnit4::class)
class PublicLegacySearchBaselineDiagnosticTest {
    private class Replay(private val body: String, private val status: Int) :
        NanoHTTPD("127.0.0.1", 0) {
        val requests = AtomicInteger()
        val searchUrl
            get() = "http://127.0.0.1:$listeningPort/search"

        override fun serve(session: IHTTPSession): Response {
            if (session.uri != "/search")
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Unknown replay route",
                )
            requests.incrementAndGet()
            return newFixedLengthResponse(
                Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR,
                "text/html; charset=utf-8",
                body,
            )
        }
    }

    @Test
    fun fixedPublicSourceSearchResponseHasNativeAndActualStageBaselines(): Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
            val bytes =
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("yckceo-public-sources.json")
                    .use { it.readBytes() }
            val assetSha = sha(bytes)
            assertEquals(
                "Fixed public asset must not be replaced",
                "eb5428f87d77070313ad91ea6eb93afdf2520dc2ee3e5b9a1b0361d1ed4ae06b",
                assetSha,
            )
            val array = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonArray
            assertEquals(4, array.size())
            val original = array[1]
            val source = GSON.fromJson(original, BookSource::class.java)
            val report =
                linkedMapOf<String, Any?>(
                    "schemaVersion" to 1,
                    "diagnosticOnly" to true,
                    "liveAcceptancePassed" to false,
                    "sourceIndex" to 1,
                    "assetSha256" to assetSha,
                    "originalJsonSha256" to sha(original.toString().toByteArray(Charsets.UTF_8)),
                    "originalJsonRepresentation" to "parsed JSON object serialization",
                    "searchKey" to "西游记",
                    "page" to 1,
                    "cloneTransport" to
                        "Only searchUrl is replaced with a same-body localhost replay; source ID and every rule remain original. Relative URL byte identity is not asserted.",
                    "status" to "running",
                )
            var replay: Replay? = null
            try {
                withContext(SuppressSourceNavigation) {
                    val response =
                        withTimeout(30_000) {
                            AnalyzeUrl(
                                    source.searchUrl ?: error("Public source has no search URL"),
                                    key = "西游记",
                                    page = 1,
                                    source = source,
                                    baseUrl = source.bookSourceUrl,
                                    coroutineContext = currentCoroutineContext(),
                                )
                                .getStrResponseAwait()
                        }
                    val body = response.body ?: error("Native search response has no body")
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    report["nativeHttpStatus"] = response.raw.code
                    report["bodyRepresentation"] =
                        "decoded response body encoded as UTF-8; not raw wire bytes"
                    report["bodySha256"] = sha(bodyBytes)
                    report["bodyBytes"] = bodyBytes.size
                    if (response.raw.code !in 200..299)
                        error("Native search returned an unsuccessful HTTP status")
                    val document = Jsoup.parse(body, response.url)
                    report["loginFormPresent"] =
                        document
                            .select("input[type=password],form[action*=login],form[action*=signin]")
                            .isNotEmpty()
                    report["challengeMarkerPresent"] =
                        Regex(
                                "cf-chl-|challenge-platform|captcha|验证码|人机验证|checking your browser",
                                RegexOption.IGNORE_CASE,
                            )
                            .containsMatchIn(body)
                    val rules = source.getSearchRule()
                    val listRule = rules.bookList ?: error("Public source has no book-list rule")
                    report["fullRuleMayFetchAdditionalResources"] =
                        Regex("java\\.(ajax|ajaxAll|connect|get|post)\\s*\\(")
                            .containsMatchIn(listRule)
                    val selectors = linkedSetOf<String>()
                    Regex("^@css:([^\\r\\n<]+)", RegexOption.IGNORE_CASE)
                        .find(listRule)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        ?.let(selectors::add)
                    Regex("\\.select\\(\\s*['\"]([^'\"]+)['\"]").findAll(listRule).forEach {
                        selectors.add(it.groupValues[1])
                    }
                    report["selectorCounts"] = selectors.map { selector ->
                        mapOf(
                            "selectorSha256" to sha(selector.toByteArray(Charsets.UTF_8)),
                            "matches" to document.select(selector).size,
                        )
                    }
                    val old = linkedMapOf<String, Any?>("status" to "running")
                    report["originalParser"] = old
                    try {
                        val rows =
                            withTimeout(30_000) {
                                AnalyzeRule(source = source)
                                    .setCoroutineContext(currentCoroutineContext())
                                    .setContent(body, response.url)
                                    .getElements(listRule)
                            }
                        old["listCount"] = rows.size
                        val names =
                            withTimeout(30_000) {
                                rows.map { row ->
                                    AnalyzeRule(SearchBook(), source)
                                        .setCoroutineContext(currentCoroutineContext())
                                        .setContent(row, response.url)
                                        .getString(rules.name)
                                }
                            }
                        old["nameHashes"] = names.map { sha(it.toByteArray(Charsets.UTF_8)) }
                        old["status"] = "complete"
                    } catch (error: Exception) {
                        recordFailure(old, error)
                    }
                    replay =
                        Replay(body, response.raw.code).apply {
                            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                        }
                    val replaySource = source.copy(searchUrl = replay!!.searchUrl)
                    val actual = linkedMapOf<String, Any?>("status" to "running")
                    report["actualWebBookReplay"] = actual
                    try {
                        val rows =
                            withTimeout(30_000) { WebBook.searchBookAwait(replaySource, "西游记", 1) }
                        actual["listCount"] = rows.size
                        actual["nameHashes"] = rows.map { sha(it.name.toByteArray(Charsets.UTF_8)) }
                        actual["status"] = "complete"
                    } catch (error: Exception) {
                        recordFailure(actual, error)
                    }
                    report["replaySearchRequests"] = replay!!.requests.get()
                    report["status"] =
                        if (old["status"] == "complete" && actual["status"] == "complete")
                            "complete"
                        else "failed"
                    if (report["status"] == "complete") {
                        report["listCountsAgree"] = old["listCount"] == actual["listCount"]
                        report["nameHashesAgree"] = old["nameHashes"] == actual["nameHashes"]
                    }
                }
            } catch (error: Exception) {
                recordFailure(report, error)
            } finally {
                replay?.stop()
                save(report)
                DartSourceEngine.clearSourceState(source)
            }
            assertTrue(
                "Public baseline diagnostic failed; inspect public-legacy-search-baseline.json (no response body or URL is recorded)",
                report["status"] == "complete",
            )
        }

    private fun recordFailure(target: MutableMap<String, Any?>, error: Exception) {
        if (
            error is CancellationException &&
                error !is kotlinx.coroutines.TimeoutCancellationException
        )
            throw error
        target["status"] = "failed"
        target["errorType"] = error.javaClass.name
        target["errorCode"] =
            when (error) {
                is SourceScriptException -> error.code
                is SourceHostException -> error.code
                is kotlinx.coroutines.TimeoutCancellationException -> "deadline_exceeded"
                else -> "native_diagnostic_failed"
            }
        // Messages, URLs, headers, source text and response bodies are intentionally absent.
    }

    private fun save(report: Map<String, Any?>) {
        val directory =
            checkNotNull(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            )
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "public-legacy-search-baseline.json")
        val temporary = File(directory, "public-legacy-search-baseline.json.tmp")
        temporary.writeText(GSON.toJson(report))
        check(temporary.renameTo(file))
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
