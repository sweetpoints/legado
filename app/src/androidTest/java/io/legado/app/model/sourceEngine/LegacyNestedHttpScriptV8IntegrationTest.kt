package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Original native URL-script phases compared with the same-owner java.ajax entry. */
@RunWith(AndroidJUnit4::class)
class LegacyNestedHttpScriptV8IntegrationTest {
    private class Server : NanoHTTPD("127.0.0.1", 0) {
        val paths = CopyOnWriteArrayList<String>()
        val base
            get() = "http://127.0.0.1:$listeningPort"

        override fun serve(session: IHTTPSession): Response {
            paths += session.uri
            return when (session.uri) {
                "/resolved",
                "/raw" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "text/plain; charset=utf-8",
                        "raw-body",
                    )
                else ->
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "Unexpected fixture path",
                    )
            }
        }
    }

    private fun source(library: String) =
        BookSource(
                bookSourceUrl = "opaque-nested-http-${UUID.randomUUID()}",
                bookSourceName = "Native URL script phase fixture",
                jsLib =
                    "globalThis.stageLibraryLoads=(globalThis.stageLibraryLoads||0)+1;" + library,
            )
            .apply { putVariable("source-value") }

    private val observePhase =
        """
        globalThis.stageRuns=(globalThis.stageRuns||0)+1;
        globalThis.stageScope=java.get('token');
        globalThis.stageSourceValue=source.getVariable();
        """
            .trimIndent()

    private fun outerScope() = SourceHostCallbacks { method, arguments ->
        require(method == "analyze.get" && arguments == listOf("token")) {
            "Unexpected outer callback"
        }
        "outer"
    }

    private suspend fun native(source: BookSource, rule: String, base: String): String? =
        withTimeout(15_000) {
            AnalyzeUrl(
                    rule,
                    source = source,
                    baseUrl = base,
                    coroutineContext = currentCoroutineContext(),
                )
                .getStrResponseAwait()
                .body
        }

    private suspend fun ajax(source: BookSource, rule: String): String? {
        val value =
            withTimeout(15_000) {
                DartSourceEngine.evaluate(
                    source,
                    "({before:java.get('token'),body:java.ajax(${GSON.toJson(rule)},8000),after:java.get('token')})",
                )
            }
                as Map<*, *>
        assertEquals("outer", value["before"])
        assertEquals("outer", value["after"])
        return value["body"] as String?
    }

    private suspend fun state(source: BookSource, runs: Int) {
        val value =
            withTimeout(15_000) {
                DartSourceEngine.evaluate(
                    source,
                    "({loads:stageLibraryLoads,runs:stageRuns,scope:stageScope,sourceValue:stageSourceValue})",
                )
            }
                as Map<*, *>
        assertEquals(1, (value["loads"] as Number).toInt())
        assertEquals(runs, (value["runs"] as Number).toInt())
        // AnalyzeUrl for java.ajax has no book/chapter RuleData: java.get must not
        // accidentally reuse the outer parser callback, while source state stays live.
        assertEquals("", value["scope"])
        assertEquals("source-value", value["sourceValue"])
    }

    @Test
    fun dynamicUrlJsAndInterpolationReuseTheOwnerWithoutLeakingOuterVariables(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = Server().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source =
                source("function resolvedUrl(){$observePhase return '${server.base}/resolved';}")
            try {
                withContext(outerScope()) {
                    val rules = listOf("@js:resolvedUrl()", "{{resolvedUrl()}}")
                    var runs = 0
                    for (rule in rules) {
                        assertEquals("raw-body", native(source, rule, server.base))
                        state(source, ++runs)
                        assertEquals("raw-body", ajax(source, rule))
                        state(source, ++runs)
                    }
                }
                assertEquals(
                    listOf("/resolved", "/resolved", "/resolved", "/resolved"),
                    server.paths.toList(),
                )
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun urlOptionJsReceivesResolvedUrlAndPreservesTheSameLibraryAndGlobals(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = Server().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source =
                source(
                    "function rewriteUrl(value){$observePhase globalThis.optionInputCorrect=(value==='${server.base}/seed');return String(value).replace('/seed','/resolved');}"
                )
            val rule = server.base + "/seed," + GSON.toJson(mapOf("js" to "rewriteUrl(result)"))
            try {
                withContext(outerScope()) {
                    assertEquals("raw-body", native(source, rule, server.base))
                    state(source, 1)
                    assertEquals(true, DartSourceEngine.evaluate(source, "optionInputCorrect"))
                    assertEquals("raw-body", ajax(source, rule))
                    state(source, 2)
                    assertEquals(true, DartSourceEngine.evaluate(source, "optionInputCorrect"))
                }
                assertEquals(listOf("/resolved", "/resolved"), server.paths.toList())
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun responseBodyJsRunsAfterNativeFetchInTheSameOwnerAndReceivesTheRawBody(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = Server().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source =
                source(
                    "function rewriteBody(value){$observePhase return String(value)+'|'+stageLibraryLoads+'|'+stageRuns+'|'+stageSourceValue+'|'+stageScope;}"
                )
            val rule = server.base + "/raw," + GSON.toJson(mapOf("bodyJs" to "rewriteBody(result)"))
            try {
                withContext(outerScope()) {
                    assertEquals("raw-body|1|1|source-value|", native(source, rule, server.base))
                    state(source, 1)
                    assertEquals("raw-body|1|2|source-value|", ajax(source, rule))
                    state(source, 2)
                }
                assertEquals(listOf("/raw", "/raw"), server.paths.toList())
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }
}
