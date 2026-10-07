package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import io.legado.app.BuildConfig
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.TocEmptyException
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public input, real production V8/WebBook only. A blocked report is never live acceptance. */
@RunWith(AndroidJUnit4::class)
class PublicSourceCorpusTest {
    private data class Stage(
        val name: String,
        var status: String = "notRun",
        var count: Int? = null,
        var length: Int? = null,
        var elapsedMs: Long = 0,
        var errorCode: String? = null,
        var errorType: String? = null,
        var errorDiagnostic: String? = null,
        var errorMessageSha256: String? = null,
        var errorWrapperKind: String? = null,
        var errorChain: List<ExceptionDiagnostic> = emptyList(),
        var classification: String? = null,
    )

    private data class ErrorOrigin(val className: String, val method: String, val line: Int)

    private data class ExceptionDiagnostic(
        val exceptionClass: String,
        val code: String?,
        val wrapperKind: String?,
        val messageSha256: String,
        val origin: ErrorOrigin?,
        val remoteExceptionTypes: List<String>,
    )

    private data class SourceResult(
        val sourceIdSha256: String,
        val inputObjectSha256: String,
        val stages: List<Stage>,
        var ingestionErrorCode: String? = null,
        var ingestionErrorType: String? = null,
    )

    private data class Report(
        val schemaVersion: Int = 1,
        val sourceCommit: String,
        val inputSha256: String,
        val inputBytes: Int,
        val asset: String = "yckceo-public-sources.json",
        val searchKey: String = "西游记",
        val page: Int = 1,
        val stageDeadlineMs: Long = 30_000,
        val startedAtEpochMs: Long,
        val sourceCount: Int,
        val sources: MutableList<SourceResult> = mutableListOf(),
        var finishedAtEpochMs: Long? = null,
        var status: String = "running",
        var liveAcceptancePassed: Boolean = false,
        var compatibilityFailureCount: Int = 0,
        var blockedStageCount: Int = 0,
        var emptyStageCount: Int = 0,
    )

    @Test
    fun publicSourcesRunSearchInfoTocAndFirstPublicChapterWithoutLogin(): Unit =
        runBlocking(Dispatchers.IO) {
            assertTrue(
                "The actual Flutter/V8 backend is mandatory",
                BuildConfig.FLUTTER_SOURCE_ENGINE,
            )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val arguments = InstrumentationRegistry.getArguments()
            val commit = arguments.getString("sourceCommit").orEmpty()
            assertTrue(
                "Pass the tested full Git HEAD as instrumentation argument sourceCommit",
                commit.matches(Regex("[0-9a-fA-F]{40}")),
            )
            // This is a test APK asset. It is not a checked-in third-party corpus.
            val bytes =
                instrumentation.context.assets.open("yckceo-public-sources.json").use {
                    it.readBytes()
                }
            val inputSha = sha256(bytes)
            arguments.getString("corpusSha256")?.let { expected ->
                assertTrue(
                    "Installed corpus differs from the supplied exact input SHA",
                    expected.equals(inputSha, ignoreCase = true),
                )
            }
            val array = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonArray
            assertTrue("Expected the four fixed public sources", array.size() == 4)
            val report =
                Report(
                    sourceCommit = commit,
                    inputSha256 = inputSha,
                    inputBytes = bytes.size,
                    startedAtEpochMs = System.currentTimeMillis(),
                    sourceCount = array.size(),
                )
            try {
                for (entry in array) {
                    val json = entry.asJsonObject
                    val identity = json.get("bookSourceUrl")?.asString.orEmpty()
                    val result =
                        SourceResult(
                            sha256(identity.toByteArray(Charsets.UTF_8)),
                            sha256(json.toString().toByteArray(Charsets.UTF_8)),
                            listOf(Stage("search"), Stage("info"), Stage("toc"), Stage("content")),
                        )
                    report.sources.add(result)
                    val source =
                        try {
                            // Use the production importer adapters, including old empty rule
                            // arrays.
                            GSON.fromJson(json, BookSource::class.java)
                        } catch (error: Exception) {
                            result.ingestionErrorCode = "invalid_source_input"
                            result.ingestionErrorType = error.javaClass.name
                            continue
                        }
                    runSource(source, result)
                    save(report)
                }
            } finally {
                report.finishedAtEpochMs = System.currentTimeMillis()
                report.compatibilityFailureCount =
                    report.sources.count { source ->
                        source.ingestionErrorCode != null ||
                            source.stages.any { it.status == "compatibilityError" }
                    }
                report.blockedStageCount =
                    report.sources.sumOf { source ->
                        source.stages.count { it.status == "blocked" }
                    }
                report.emptyStageCount =
                    report.sources.sumOf { source ->
                        source.stages.count { it.status == "emptyResults" }
                    }
                report.liveAcceptancePassed =
                    report.sources.size == report.sourceCount &&
                        report.sources.all { source ->
                            source.ingestionErrorCode == null &&
                                source.stages.all { it.status == "success" }
                        }
                report.status =
                    when {
                        report.compatibilityFailureCount > 0 -> "compatibilityFailed"
                        report.liveAcceptancePassed -> "liveChainCompleted"
                        report.blockedStageCount > 0 -> "blocked"
                        else -> "inconclusive"
                    }
                save(report)
            }
            assertTrue(
                "Public-source compatibility errors occurred; inspect public-source-corpus-results.json (no response bodies are reported)",
                report.compatibilityFailureCount == 0,
            )
        }

    private suspend fun runSource(source: BookSource, result: SourceResult) {
        val search = result.stages[0]
        val rows = execute(search) { WebBook.searchBookAwait(source, "西游记", 1) } ?: return
        search.count = rows.size
        if (rows.isEmpty()) {
            search.status = "emptyResults"
            search.errorCode = "empty_search_results"
            return
        }
        val book = rows.first().toBook()
        val info = result.stages[1]
        val detailed = execute(info) { WebBook.getBookInfoAwait(source, book) } ?: return
        info.count = 1
        info.length = detailed.intro.orEmpty().length
        val toc = result.stages[2]
        val chapters =
            execute(toc) { WebBook.getChapterListAwait(source, detailed).getOrThrow() } ?: return
        toc.count = chapters.size
        if (chapters.isEmpty()) {
            toc.status = "emptyResults"
            toc.errorCode = "empty_toc_results"
            return
        }
        val content = result.stages[3]
        // A volume heading is not a chapter. Never explicitly invoke login or purchase.
        val chapter = chapters.firstOrNull { !it.isVolume }
        if (chapter == null) {
            content.status = "emptyResults"
            content.errorCode = "no_non_volume_chapter"
            return
        }
        if (chapter.isVip) {
            content.status = "blocked"
            content.errorCode = "vip_chapter_not_requested"
            content.classification = "accessConstraint"
            return
        }
        val text =
            execute(content) {
                WebBook.getContentAwait(source, detailed, chapter, needSave = false)
            } ?: return
        content.length = text.length
        content.count = if (text.isBlank()) 0 else 1
        if (text.isBlank()) {
            content.status = "emptyResults"
            content.errorCode = "empty_content"
        }
        // Deliberately do not emit names, URLs, script snippets or chapter content.
    }

    private suspend fun <T> execute(stage: Stage, block: suspend () -> T): T? {
        val start = System.currentTimeMillis()
        return try {
            withTimeout(30_000) { block() }.also { stage.status = "success" }
        } catch (error: TimeoutCancellationException) {
            stage.status = "blocked"
            stage.errorCode = "stage_deadline"
            recordDiagnostic(stage, error)
            stage.classification = "deadlineUnclassified"
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordDiagnostic(stage, error)
            val code = structuredCode(error)
            stage.errorCode = code
            when {
                error is TocEmptyException || error is ContentEmptyException -> {
                    stage.status = "emptyResults"
                    stage.classification = "emptyStageResult"
                }
                isNetworkUnavailable(error, code) -> {
                    stage.status = "blocked"
                    stage.classification = "networkUnavailable"
                }
                else -> {
                    stage.status = "compatibilityError"
                    stage.classification = "engineOrSourceCompatibility"
                }
            }
            null
        } finally {
            stage.elapsedMs = System.currentTimeMillis() - start
        }
    }

    private fun recordDiagnostic(stage: Stage, error: Throwable) {
        stage.errorType = error.javaClass.name
        stage.errorWrapperKind = wrapperKind(error.message.orEmpty())
        stage.errorChain = exceptionDiagnostics(error)
        val message = error.message.orEmpty()
        stage.errorMessageSha256 = sha256(message.toByteArray(Charsets.UTF_8))
        // Never persist the original message: it can include source code, URLs,
        // request headers or book content. Only bounded identifier templates survive.
        val bounded = message.take(4096)
        val missing =
            Regex("""ReferenceError:\s*([A-Za-z_$][A-Za-z0-9_$]{0,63})\s+is not defined\b""")
                .find(bounded)
        if (missing != null) {
            stage.errorDiagnostic = "ReferenceError: ${missing.groupValues[1]} is not defined"
            return
        }
        if (bounded.contains("TypeError:")) {
            val absentMethod =
                Regex("""\b([A-Za-z_$][A-Za-z0-9_$]{0,63})\s+is not a function\b""")
                    .find(bounded)
                    ?.groupValues
                    ?.get(1)
            if (
                absentMethod in
                    setOf(
                        "toArray",
                        "select",
                        "attr",
                        "text",
                        "html",
                        "outerHtml",
                        "getString",
                        "getElement",
                        "getElements",
                        "get",
                        "put",
                        "getKey",
                        "getTag",
                        "getVariable",
                        "post",
                        "headers",
                        "ajax",
                        "md5Encode",
                    )
            ) {
                stage.errorDiagnostic = "TypeError: method $absentMethod is not a function"
                return
            }
        }
        val unsupported =
            Regex(
                    """legacy\.unsupported_api:\s*((?:java|source)\.[A-Za-z_][A-Za-z0-9_]{0,63}(?:\.[A-Za-z_][A-Za-z0-9_]{0,63}){0,2})\b"""
                )
                .find(bounded)
        if (unsupported != null) {
            stage.errorDiagnostic = "legacy.unsupported_api: ${unsupported.groupValues[1]}"
            return
        }
        stage.errorDiagnostic =
            when {
                bounded.contains("SyntaxError: Malformed arrow function parameter list") ->
                    "SyntaxError: Malformed arrow function parameter list"
                bounded.contains("SyntaxError: Unexpected token") ->
                    "SyntaxError: Unexpected token (token omitted)"
                bounded.contains("SyntaxError: Unexpected identifier") ->
                    "SyntaxError: Unexpected identifier (value omitted)"
                bounded.contains("nested_script_requires_migration") ->
                    "nested_script_requires_migration"
                else -> "Unclassified diagnostic; original message omitted"
            }
    }

    private fun wrapperKind(message: String): String? {
        // Names are fixed framework/runtime types, never arbitrary message fragments.
        return listOf(
                "ReferenceError",
                "TypeError",
                "SyntaxError",
                "RangeError",
                "EvalError",
                "FormatException",
                "StateError",
                "UnsupportedError",
                "ArgumentError",
                "NoSuchMethodError",
                "SocketException",
                "HandshakeException",
                "HttpException",
                "ClientException",
                "PlatformException",
                "EngineException",
                "SourceScriptException",
                "IllegalStateException",
                "java.lang.IllegalStateException",
                "java.lang.IllegalArgumentException",
            )
            .firstOrNull { message.startsWith("$it:") || message.startsWith("$it(") }
    }

    private fun exceptionDiagnostics(error: Throwable): List<ExceptionDiagnostic> {
        val seen = IdentityHashMap<Throwable, Boolean>()
        val result = mutableListOf<ExceptionDiagnostic>()
        var current: Throwable? = error
        while (current != null && result.size < 8 && seen.put(current, true) == null) {
            val message = current.message.orEmpty()
            val wrapper = wrapperKind(message)
            val codeText =
                if (wrapper != null && message.startsWith("$wrapper:"))
                    message.substring(wrapper.length + 1).trimStart()
                else message
            val protocolCode =
                Regex("^([a-z][a-z0-9_.]{0,63}):").find(codeText)?.groupValues?.get(1)?.takeUnless {
                    it in setOf("http", "https", "file", "content", "data")
                }
            val origin =
                current.stackTrace
                    .firstOrNull { frame ->
                        (frame.className.startsWith("io.legado.app.") ||
                            frame.className.startsWith("io.flutter.")) &&
                            frame.className.matches(Regex("[A-Za-z0-9_.$]+")) &&
                            frame.methodName.matches(Regex("[A-Za-z0-9_$]+"))
                    }
                    ?.let { ErrorOrigin(it.className, it.methodName, it.lineNumber) }
            result.add(
                ExceptionDiagnostic(
                    current.javaClass.name,
                    when (current) {
                        is SourceScriptException -> current.code
                        is SourceHostException -> current.code
                        else -> protocolCode
                    },
                    wrapper,
                    sha256(message.toByteArray(Charsets.UTF_8)),
                    origin,
                    (current as? SourceHostException)?.exceptionTypes?.take(8).orEmpty(),
                )
            )
            current = current.cause
        }
        return result
    }

    private fun structuredCode(error: Throwable): String {
        if (error is SourceScriptException) return error.code
        if (error is SourceHostException) return error.code
        if (error is TocEmptyException) return "empty_toc_results"
        if (error is ContentEmptyException) return "empty_content"
        return Regex("^([a-z][a-z0-9_]+):").find(error.message.orEmpty())?.groupValues?.get(1)
            ?: "unclassified_exception"
    }

    private fun isNetworkUnavailable(error: Throwable, code: String): Boolean {
        if (
            error is SourceScriptException ||
                code in setOf("script_error", "syntax_error", "nested_script_requires_migration")
        )
            return false
        if (
            code in
                setOf(
                    "network_error",
                    "network_unavailable",
                    "network_timeout",
                    "http_error",
                    "http_status_error",
                )
        )
            return true
        val causes = generateSequence(error) { it.cause }.take(8)
        if (
            causes.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketTimeoutException ||
                    it is SSLException
            }
        )
            return true
        // Current Dart outer boundary preserves these exact transport exception types
        // as engine_failed text; never classify generic IOException or script_error this way.
        return code == "engine_failed" &&
            Regex(
                    "^engine_failed: (SocketException|HandshakeException|HttpException|ClientException):"
                )
                .containsMatchIn(error.message.orEmpty())
    }

    private fun save(report: Report) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = checkNotNull(context.getExternalFilesDir(null))
        check(directory.isDirectory || directory.mkdirs())
        val destination = File(directory, "public-source-corpus-results.json")
        val temporary = File(directory, "public-source-corpus-results.json.tmp")
        temporary.writeText(GSON.toJson(report))
        check(temporary.renameTo(destination)) { "Could not publish corpus acceptance report" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
