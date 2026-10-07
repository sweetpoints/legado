package io.legado.app.model.sourceEngine

import io.legado.app.help.JsExtensions
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class LegacyJavaHostTest {
    private class Extensions : JsExtensions {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val job = Job()
        override fun getSource() = null
        override fun getTag() = "fixture"
        override fun getSourceNavigationContext() = job
        private fun record(name: String, vararg args: Any?): String {
            calls.add(name to args.toList()); return "result"
        }
        override fun log(msg: Any?): Any? { record("log", msg); return msg }
        override fun toast(msg: Any?) { record("toast", msg) }
        override fun longToast(msg: Any?) { record("longToast", msg) }
        override fun logType(any: Any?) { record("logType", any) }
        override fun t2s(text: String) = record("t2s", text)
        override fun s2t(text: String) = record("s2t", text)
        override fun timeFormat(time: Long) = record("timeFormat", time)
        override fun timeFormatUTC(time: Long, format: String, sh: Int) = record("timeFormatUTC", time, format, sh)
        override fun getCookie(tag: String) = record("getCookie", tag)
        override fun getCookie(tag: String, key: String?) = record("getCookie", tag, key)
    }

    @Test fun dispatchesActualLegacyMethodsAndPreservesMillisAndCookieOverloads() {
        val ext = Extensions()
        val value = mapOf("x" to 1)
        assertSame(value, LegacyJavaHost.call(ext, "javaHost.log", listOf(value)))
        for (name in listOf("toast", "longToast", "logType")) LegacyJavaHost.call(ext, "javaHost.$name", listOf(null))
        for (name in listOf("t2s", "s2t")) assertEquals("result", LegacyJavaHost.call(ext, "javaHost.$name", listOf("text")))
        LegacyJavaHost.call(ext, "javaHost.timeFormat", listOf(1234.0))
        LegacyJavaHost.call(ext, "javaHost.timeFormatUTC", listOf(1234.0, "yyyy", 28800000.0))
        LegacyJavaHost.call(ext, "javaHost.getCookie", listOf("tag"))
        LegacyJavaHost.call(ext, "javaHost.getCookie", listOf("tag", null))
        assertEquals("timeFormatUTC" to listOf(1234L, "yyyy", 28800000), ext.calls[7])
        assertEquals(listOf("tag", null), ext.calls.last().second)
    }

    @Test fun usesRealJsExtensionsUtcFormattingWithMillisOffset() {
        val extensions = object : JsExtensions {
            override fun getSource() = null
            override fun getTag() = "fixture"
        }
        assertEquals("1970-01-01 08:00", LegacyJavaHost.call(
            extensions, "javaHost.timeFormatUTC", listOf(0L, "yyyy-MM-dd HH:mm", 28800000),
        ))
    }

    @Test fun rejectsUnknownNamesInvalidArgumentsAndCancelledTasks() {
        val ext = Extensions()
        for ((name, args) in listOf("getClass" to emptyList(), "timeFormat" to listOf(1.5), "getCookie" to listOf("tag", 1))) {
            assertThrows(Exception::class.java) { LegacyJavaHost.call(ext, "javaHost.$name", args) }
        }
        assertTrue(ext.calls.isEmpty())
        ext.job.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { LegacyJavaHost.call(ext, "javaHost.log", listOf("x")) }
        assertTrue(ext.calls.isEmpty())
    }
}
