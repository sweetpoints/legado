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

    @Test fun usesRealHmacChapterAndUuidContracts() {
        val extensions = object : JsExtensions {
            override fun getSource() = null
            override fun getTag() = "fixture"
        }
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            LegacyJavaHost.call(extensions, "javaHost.HMacHex", listOf("The quick brown fox jumps over the lazy dog", "HmacSHA256", "key")))
        assertEquals("第123章", LegacyJavaHost.call(extensions, "javaHost.toNumChapter", listOf("第一百二十三章")))
        assertNull(LegacyJavaHost.call(extensions, "javaHost.toNumChapter", listOf(null)))
        val uuid = LegacyJavaHost.call(extensions, "javaHost.randomUUID", emptyList()) as String
        assertEquals(uuid, java.util.UUID.fromString(uuid).toString())
    }

    @Test fun repeatedChapterCreationHasNoNativeRegistryAndRetainedStateStillWorks() {
        val ext = object : JsExtensions {
            override fun getSource() = null
            override fun getTag() = "fixture"
        }
        fun create(): Map<*, *> = (LegacyJavaHost.call(ext, "javaHost.cryptoCreate",
            listOf("AES/ECB/PKCS5Padding", null), "owner") as Map<*, *>)["__legacyCryptoState"] as Map<*, *>
        val retained = create()
        repeat(600) { create() }
        fun invoke(state: Map<*, *>, operation: String, value: Any?, owner: String = "owner") =
            LegacyJavaHost.call(ext, "javaHost.cryptoCall", listOf(state, operation, listOf(value)), owner) as Map<*, *>
        val encrypted = invoke(retained, "encryptHex", "hello")
        assertEquals("hello", invoke(encrypted["state"] as Map<*, *>, "decryptStr", encrypted["value"])["value"])
        assertThrows(IllegalArgumentException::class.java) { invoke(retained, "encryptHex", "hello", "another") }
    }

    @Test fun jsonCryptoMatchesOriginalEcbCbcAndGcmOperationSequences() {
        val ext = object : JsExtensions {
            override fun getSource() = null
            override fun getTag() = "fixture"
        }
        for (mode in listOf("AES/ECB/PKCS5Padding", "AES/CBC/PKCS5Padding", "AES/GCM/NoPadding")) {
            val initialIv = if (mode.contains("CBC")) List(16) { 1 } else null
            var state = (LegacyJavaHost.call(ext, "javaHost.cryptoCreate", listOf(mode, null, initialIv), "owner") as Map<*, *>)["__legacyCryptoState"] as Map<*, *>
            val key = (state["key"] as List<*>).map { (it as Number).toByte() }.toByteArray()
            val baseline = ext.createSymmetricCrypto(mode, key, initialIv?.map { it.toByte() }?.toByteArray())
            fun invoke(op: String, value: Any?): Any? {
                val response = LegacyJavaHost.call(ext, "javaHost.cryptoCall", listOf(state, op, listOf(value)), "owner") as Map<*, *>
                state = response["state"] as Map<*, *>
                return response["value"]
            }
            repeat(2) {
                val expected = baseline.encryptHex("payload")
                val actual = invoke("encryptHex", "payload") as String
                if (!mode.contains("GCM")) assertTrue("Configured-parameter encryption must match", expected == actual)
                else assertEquals(expected.length, actual.length)
                val expectedDecryption = runCatching { baseline.decryptStr(actual) }
                val actualDecryption = runCatching { invoke("decryptStr", actual) }
                assertEquals(expectedDecryption.isSuccess, actualDecryption.isSuccess)
                if (expectedDecryption.isSuccess) assertEquals(expectedDecryption.getOrNull(), actualDecryption.getOrNull())
                else assertEquals(expectedDecryption.exceptionOrNull()!!.javaClass, actualDecryption.exceptionOrNull()!!.javaClass)
            }
            val nextIv = List(16) { 2 }
            baseline.setIv(nextIv.map { it.toByte() }.toByteArray())
            invoke("setIv", nextIv)
            val oldOutcome = runCatching { baseline.encryptHex("after-setIv") }
            val newOutcome = runCatching { invoke("encryptHex", "after-setIv") }
            assertEquals(oldOutcome.isSuccess, newOutcome.isSuccess)
            if (oldOutcome.isSuccess) {
                assertTrue("setIv must remain per-object", oldOutcome.getOrNull() == newOutcome.getOrNull())
                assertEquals("after-setIv", invoke("decryptStr", newOutcome.getOrNull()))
            } else assertEquals(oldOutcome.exceptionOrNull()!!.javaClass, newOutcome.exceptionOrNull()!!.javaClass)
        }
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
