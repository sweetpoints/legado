package io.legado.app.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Native V8 tests through production entry points and the shipped CryptoJS asset. */
@RunWith(AndroidJUnit4::class)
class CryptoJsV8CompatibilityTest {
    @Test
    fun supportsHashesHmacAndBase64() {
        val result = evalWithCrypto(
            """
                [
                    CryptoJS.MD5('abc').toString(),
                    CryptoJS.SHA1('abc').toString(),
                    CryptoJS.SHA256('abc').toString(),
                    CryptoJS.HmacSHA256(
                        'The quick brown fox jumps over the lazy dog',
                        'key'
                    ).toString(),
                    CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse('hello'))
                ].join('|');
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "900150983cd24fb0d6963f7d28e17f72",
                "a9993e364706816aba3e25717850c26c9cd0d89d",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                "aGVsbG8=",
            ).joinToString("|"),
            result,
        )
    }

    @Test
    fun supportsAesWithExplicitKeyAndIv() {
        val result = evalWithCrypto(
            """
                var plaintext = CryptoJS.enc.Hex.parse('00112233445566778899aabbccddeeff');
                var key = CryptoJS.enc.Hex.parse('000102030405060708090a0b0c0d0e0f');
                var iv = CryptoJS.enc.Hex.parse('00000000000000000000000000000000');
                CryptoJS.AES.encrypt(plaintext, key, {
                    iv: iv,
                    mode: CryptoJS.mode.CBC,
                    padding: CryptoJS.pad.NoPadding
                }).ciphertext.toString();
            """.trimIndent(),
        )

        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", result)
    }

    @Test
    fun usesSecureRandomForWordArraysAndPassphraseSalts() {
        val result = evalWithCrypto(
            """
                Math.random = function() {
                    throw new Error('Math.random must not be used');
                };
                var firstRandom = CryptoJS.lib.WordArray.random(32).toString();
                var secondRandom = CryptoJS.lib.WordArray.random(32).toString();
                var firstCipher = CryptoJS.AES.encrypt('正文', 'password').toString();
                var secondCipher = CryptoJS.AES.encrypt('正文', 'password').toString();
                [
                    firstRandom.length === 64,
                    firstRandom !== secondRandom,
                    firstCipher !== secondCipher,
                    firstCipher.indexOf('U2FsdGVkX1') === 0,
                    CryptoJS.AES.decrypt(firstCipher, 'password')
                        .toString(CryptoJS.enc.Utf8) === '正文',
                    CryptoJS.AES.decrypt(secondCipher, 'password')
                        .toString(CryptoJS.enc.Utf8) === '正文'
                ].every(function(value) { return value; });
            """.trimIndent(),
        )

        assertEquals(true, result)
    }

    @Test
    fun blankLibraryInstallsCryptoAtEveryRetainedRssEntryPoint() {
        val source = source(jsLib = " ")
        val script = "CryptoJS.SHA256('abc').toString()"
        assertEquals(SHA256_ABC, source.evalJS(script))
        assertEquals(SHA256_ABC, AnalyzeRule(source = source).evalJS(script))
        assertEquals(SHA256_ABC, AnalyzeUrl(
            source.sourceUrl,
            source = source,
            headerMapF = emptyMap(),
        ).evalJS(script))
    }

    @Test
    fun customLibraryCanUseCryptoAndRuntimeBindings() {
        val source = source(jsLib = """
            function runtimeDescription(runtime) {
                return [typeof runtime.java, typeof runtime.java.log,
                    typeof runtime.source, typeof runtime.cache,
                    CryptoJS.SHA256('abc').toString()].join('|');
            }
        """.trimIndent())
        val expected = "object|function|object|object|$SHA256_ABC"
        assertEquals(expected, source.evalJS("runtimeDescription(this)"))
        assertEquals(expected, AnalyzeRule(source = source).evalJS("runtimeDescription(this)"))
    }

    @Test
    fun customLibraryInitializationAndRuleVariablesDoNotLeakBetweenRequests() {
        val source = source(jsLib = "var libraryMarker = 'ready';")
        assertEquals("ready|private", source.evalJS("var requestOnly = 'private'; libraryMarker + '|' + requestOnly"))
        assertEquals("ready|undefined", source.evalJS("libraryMarker + '|' + typeof requestOnly"))
    }

    @Test
    fun independentSourcesHaveIndependentCryptoAndBuiltinObjects() {
        val first = source()
        val second = source()
        first.evalJS("CryptoJS.__testMarker = 'first'; Array.prototype.__testMarker = 'first';")
        assertEquals("undefined|undefined", second.evalJS(
            "typeof CryptoJS.__testMarker + '|' + typeof Array.prototype.__testMarker",
        ))
        assertEquals(SHA256_ABC, second.evalJS("CryptoJS.SHA256('abc').toString()"))
    }

    @Test
    fun sourceGlobalsAreSharedAcrossEntryPointsAndRemainSourceSpecific() {
        val first = source(jsLib = "var libraryMarker = 'ready';")
        val second = source(jsLib = first.jsLib)
        AnalyzeUrl(first.sourceUrl, source = first, headerMapF = emptyMap())
            .evalJS("globalThis.settings = { language: 'zh-CN' };")
        assertEquals("zh-CN|ready", AnalyzeRule(source = first).evalJS(
            "globalThis.settings.language + '|' + libraryMarker",
        ))
        assertEquals("undefined", second.evalJS("typeof globalThis.settings"))
        first.evalJS("delete globalThis.settings;")
        assertEquals("undefined", AnalyzeRule(source = first).evalJS("typeof globalThis.settings"))
    }

    @Test
    fun clearingOneSourceStateLeavesOtherSourceStateUsable() {
        val first = source(jsLib = "var cleanupLibraryMarker = true;")
        val second = source(jsLib = first.jsLib)
        first.evalJS("globalThis.sourceKind = 'first';")
        second.evalJS("globalThis.sourceKind = 'second';")
        first.clearSharedGlobalState()
        assertEquals("undefined", first.evalJS("typeof globalThis.sourceKind"))
        assertEquals("second", second.evalJS("globalThis.sourceKind"))
        SharedJsScope.remove(first.jsLib)
        assertEquals("undefined", second.evalJS("typeof globalThis.sourceKind"))
    }

    @Test
    fun cryptoRemainsStableUnderConcurrentRequests() {
        val source = source(jsLib = "var concurrentLibrary = true;")
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = List(8) {
                executor.submit(Callable {
                    check(start.await(10, TimeUnit.SECONDS))
                    List(5) { source.evalJS("CryptoJS.SHA256('abc').toString()") }
                })
            }
            start.countDown()
            results.flatMap { it.get(60, TimeUnit.SECONDS) }
                .forEach { assertEquals(SHA256_ABC, it) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun evalWithCrypto(script: String): Any? = source().evalJS(script)

    private fun source(jsLib: String? = null) = RssSource(
        sourceUrl = "https://127.0.0.1/v8-crypto/${UUID.randomUUID()}",
        sourceName = "V8 crypto compatibility test",
        jsLib = jsLib,
    )

    companion object {
        private const val SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
