package io.legado.app.model.sourceEngine

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.BuildConfig
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.image.CoverImage
import io.legado.app.data.image.GlideCoverImageLoader
import io.legado.app.data.repository.CoverConfiguration
import io.legado.app.data.repository.CoverRequest
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.model.webBook.BookInfo
import io.legado.app.model.webBook.BookList
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in public-source diagnosis. Never part of the deterministic offline inventory. */
@RunWith(AndroidJUnit4::class)
class Dingdian6PublicSourceDiagnosticTest {
    private data class Stage(
        val name: String,
        var status: String = "running",
        var httpStatus: Int? = null,
        var redirectedOrigin: String? = null,
        var responseLength: Int? = null,
        var httpMetadataObserved: Boolean = false,
        var count: Int? = null,
        var resultSha256: String? = null,
        var errorCode: String? = null,
        var errorType: String? = null,
        var errorMessageSha256: String? = null,
        var classification: String? = null,
        var elapsedMs: Long = 0,
    )

    private data class Report(
        val schemaVersion: Int = 1,
        val diagnosticOnly: Boolean = true,
        val sourceCommit: String,
        val sourceAssetSha256: String,
        val sourceIdSha256: String,
        val asset: String = "dingdian6-public-source.json",
        val searchKey: String = "西游记",
        val page: Int = 1,
        val stageDeadlineMs: Long = 30_000,
        val httpMetadataContract: String =
            "Observed from native baseline initial fetch only; WebBook/V8 does not expose HTTP Response",
        val startedAtEpochMs: Long = System.currentTimeMillis(),
        val stages: MutableList<Stage> = mutableListOf(),
        var finishedAtEpochMs: Long? = null,
        var liveChainPassed: Boolean = false,
        var status: String = "running",
    )

    private class DiagnosticFailure(val code: String, val classification: String) :
        IllegalStateException(code)

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha(text: String) = sha(text.toByteArray(Charsets.UTF_8))

    private suspend fun <T> stage(report: Report, name: String, block: suspend (Stage) -> T): T {
        val stage = Stage(name)
        report.stages.add(stage)
        val start = System.currentTimeMillis()
        try {
            return withTimeout(30_000) { block(stage) }.also { stage.status = "passed" }
        } catch (failure: Throwable) {
            stage.status = "failed"
            stage.errorType = failure.javaClass.name
            stage.errorMessageSha256 = sha(failure.message.orEmpty())
            stage.errorCode =
                when (failure) {
                    is DiagnosticFailure -> failure.code
                    is SourceScriptException -> failure.code
                    is SourceHostException -> failure.code
                    is TimeoutCancellationException -> "stage_deadline_exceeded"
                    else -> "unclassified_exception"
                }
            stage.classification =
                when (failure) {
                    is DiagnosticFailure -> failure.classification
                    is TimeoutCancellationException -> "deadlineBlocked"
                    is SourceHostException ->
                        if (failure.code in setOf("network_error", "network_timeout"))
                            "networkBlocked"
                        else "compatibilityError"
                    is SourceScriptException ->
                        if (failure.code in setOf("network_error", "network_timeout"))
                            "networkBlocked"
                        else "compatibilityError"
                    else -> "unclassified"
                }
            throw failure
        } finally {
            stage.elapsedMs = System.currentTimeMillis() - start
        }
    }

    private fun observe(stage: Stage, response: StrResponse) {
        stage.httpMetadataObserved = true
        stage.httpStatus = response.code()
        val uri = URI(response.url)
        stage.redirectedOrigin =
            URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString()
        stage.responseLength = response.body?.length
        if (response.code() !in 200..299)
            throw DiagnosticFailure("http_${response.code()}", "networkBlocked")
        if (response.body.isNullOrBlank()) throw DiagnosticFailure("empty_response", "emptyResults")
    }

    private fun signature(books: List<SearchBook>, base: String) = books.map {
        listOf(it.name, it.author, NetworkUtils.getAbsoluteURL(base, it.bookUrl))
    }

    @Test
    fun publicDingdian6NativeAndV8ChainsMustMatchAndLoadAnActualInfoCover(): Unit {
        runBlocking(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val arguments = InstrumentationRegistry.getArguments()
            assertTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
            val commit = arguments.getString("sourceCommit").orEmpty()
            assertTrue(
                "A full tested Git HEAD is required",
                commit.matches(Regex("[0-9a-fA-F]{40}")),
            )
            val expectedSha = arguments.getString("sourceAssetSha256").orEmpty()
            assertTrue(
                "The exact public asset SHA is mandatory",
                expectedSha.matches(Regex("[0-9a-fA-F]{64}")),
            )
            val bytes =
                instrumentation.context.assets.open("dingdian6-public-source.json").use {
                    it.readBytes()
                }
            assertEquals(
                "Installed public asset must match the supplied SHA",
                expectedSha.lowercase(),
                sha(bytes),
            )
            val source = GSON.fromJson(bytes.toString(Charsets.UTF_8), BookSource::class.java)
            assertTrue(
                "Only the explicitly authorized public source is allowed",
                URI(source.bookSourceUrl).host in setOf("www.dingdian6.com", "dingdian6.com"),
            )
            assertTrue(
                "This diagnostic is restricted to the approved declarative source",
                source.mainJs.isNullOrBlank() && source.jsLib.isNullOrBlank(),
            )
            val report =
                Report(
                    sourceCommit = commit,
                    sourceAssetSha256 = sha(bytes),
                    sourceIdSha256 = sha(source.bookSourceUrl),
                )
            val destination =
                File(
                    requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                    "dingdian6-public-source-results.json",
                )
            var inserted = false
            var nativeSearch: List<SearchBook> = emptyList()
            var searchBase = ""
            var nativeBook: Book? = null
            var actualBook: Book? = null
            var loaded: Bitmap? = null
            val fallback =
                Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                }
            try {
                stage(report, "sourceRegistration") {
                    if (appDb.bookSourceDao.getBookSource(source.bookSourceUrl) != null)
                        throw DiagnosticFailure("existing_source_not_overwritten", "fixtureBlocked")
                    appDb.bookSourceDao.insert(source)
                    inserted = true
                }
                nativeSearch =
                    stage(report, "nativeSearch") { s ->
                        val data = RuleData()
                        val url =
                            AnalyzeUrl(
                                mUrl = requireNotNull(source.searchUrl),
                                key = report.searchKey,
                                page = 1,
                                baseUrl = source.bookSourceUrl,
                                source = source,
                                ruleData = data,
                                coroutineContext = currentCoroutineContext(),
                            )
                        val response = url.getStrResponseAwait(useWebView = false)
                        observe(s, response)
                        searchBase = response.url
                        BookList.analyzeBookList(source, data, url, response.url, response.body)
                            .also {
                                if (it.isEmpty())
                                    throw DiagnosticFailure("empty_search", "emptyResults")
                                s.count = it.size
                                s.resultSha256 = sha(GSON.toJson(signature(it, searchBase)))
                            }
                    }
                val actualSearch =
                    stage(report, "v8Search") { s ->
                        WebBook.searchBookAwait(source, report.searchKey, 1).also {
                            if (it.isEmpty())
                                throw DiagnosticFailure("empty_search", "emptyResults")
                            assertEquals(
                                signature(nativeSearch, searchBase),
                                signature(it, searchBase),
                            )
                            s.count = it.size
                            s.resultSha256 = sha(GSON.toJson(signature(it, searchBase)))
                        }
                    }
                nativeBook = nativeSearch.first().toBook()
                nativeBook!!.bookUrl = NetworkUtils.getAbsoluteURL(searchBase, nativeBook!!.bookUrl)
                actualBook = actualSearch.first().toBook()
                stage(report, "nativeInfo") { s ->
                    val book = requireNotNull(nativeBook)
                    val response =
                        AnalyzeUrl(
                                mUrl = book.bookUrl,
                                source = source,
                                ruleData = book,
                                coroutineContext = currentCoroutineContext(),
                            )
                            .getStrResponseAwait(useWebView = false)
                    observe(s, response)
                    BookInfo.analyzeBookInfo(
                        source,
                        book,
                        book.bookUrl,
                        response.url,
                        response.body,
                        true,
                    )
                    if (book.tocUrl.isBlank())
                        throw DiagnosticFailure("empty_toc_url", "emptyResults")
                    s.count = 1
                    s.resultSha256 =
                        sha(GSON.toJson(listOf(book.name, book.author, book.tocUrl, book.coverUrl)))
                }
                stage(report, "v8Info") { s ->
                    val book = WebBook.getBookInfoAwait(source, requireNotNull(actualBook))
                    val native = requireNotNull(nativeBook)
                    assertEquals(native.name, book.name)
                    assertEquals(native.author, book.author)
                    assertEquals(
                        NetworkUtils.getAbsoluteURL(native.bookUrl, native.tocUrl),
                        NetworkUtils.getAbsoluteURL(book.bookUrl, book.tocUrl),
                    )
                    assertEquals(native.coverUrl, book.coverUrl)
                    s.count = 1
                    s.resultSha256 =
                        sha(GSON.toJson(listOf(book.name, book.author, book.tocUrl, book.coverUrl)))
                }
                val nativeChapters =
                    stage(report, "nativeToc") { s ->
                        val book = requireNotNull(nativeBook)
                        val response =
                            AnalyzeUrl(
                                    mUrl = book.tocUrl,
                                    baseUrl = book.bookUrl,
                                    source = source,
                                    ruleData = book,
                                    coroutineContext = currentCoroutineContext(),
                                )
                                .getStrResponseAwait(useWebView = false)
                        observe(s, response)
                        BookChapterList.analyzeChapterList(
                                source,
                                book,
                                book.tocUrl,
                                response.url,
                                response.body,
                            )
                            .also {
                                if (it.isEmpty())
                                    throw DiagnosticFailure("empty_toc", "emptyResults")
                                s.count = it.size
                                s.resultSha256 =
                                    sha(
                                        GSON.toJson(
                                            it.map { chapter ->
                                                listOf(chapter.title, chapter.getAbsoluteURL())
                                            }
                                        )
                                    )
                            }
                    }
                stage(report, "v8Toc") { s ->
                    val chapters =
                        WebBook.getChapterListAwait(source, requireNotNull(actualBook)).getOrThrow()
                    assertFalse(
                        "A live chain may not succeed with an empty TOC",
                        chapters.isEmpty(),
                    )
                    assertEquals(
                        nativeChapters.map { listOf(it.title, it.getAbsoluteURL()) },
                        chapters.map { listOf(it.title, it.getAbsoluteURL()) },
                    )
                    s.count = chapters.size
                    s.resultSha256 =
                        sha(GSON.toJson(chapters.map { listOf(it.title, it.getAbsoluteURL()) }))
                }
                stage(report, "actualCoverPixels") { s ->
                    val book = requireNotNull(actualBook)
                    if (book.coverUrl.isNullOrBlank())
                        throw DiagnosticFailure("empty_info_cover", "emptyResults")
                    val result =
                        GlideCoverImageLoader(instrumentation.targetContext)
                            .load(CoverRequest.from(book), CoverConfiguration(fallback), 12, 18)
                    assertFalse("Default covers are not live cover acceptance", result.needsTitle)
                    loaded = (result.image as CoverImage.Static).bitmap
                    assertTrue(loaded!!.width > 0 && loaded!!.height > 0)
                    val pixels = IntArray(loaded!!.width * loaded!!.height)
                    loaded!!.getPixels(
                        pixels,
                        0,
                        loaded!!.width,
                        0,
                        0,
                        loaded!!.width,
                        loaded!!.height,
                    )
                    assertTrue(
                        "The network image must not be the supplied white fallback",
                        pixels.any { it != Color.WHITE },
                    )
                    s.count = 1
                    s.responseLength = pixels.size
                    s.resultSha256 = sha(pixels.joinToString(","))
                }
                report.liveChainPassed = true
                report.status = "passed"
            } catch (_: Throwable) {
                report.status = "failed"
            } finally {
                report.finishedAtEpochMs = System.currentTimeMillis()
                destination.writeText(GSON.toJson(report))
                loaded?.takeIf { it !== fallback }?.recycle()
                fallback.recycle()
                runCatching { DartSourceEngine.clearSourceState(source) }
                if (inserted) appDb.bookSourceDao.delete(source)
            }
            assertTrue(
                "Diagnostic live chain failed or was blocked; see the secret-safe external report",
                report.liveChainPassed,
            )
        }
    }
}
