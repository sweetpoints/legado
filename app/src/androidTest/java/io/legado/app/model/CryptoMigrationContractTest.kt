package io.legado.app.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.BuildConfig
import io.legado.app.data.entities.BookSource
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.model.sourceEngine.DartSourceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import io.legado.app.help.CacheManager
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** New V8 migration contracts; this does not establish full CryptoJS compatibility. */
@RunWith(AndroidJUnit4::class)
class CryptoMigrationContractTest {
    private fun source(suffix: String) =
        BookSource(
            bookSourceUrl = "https://crypto-contract.invalid/$suffix",
            bookSourceName = "Crypto migration",
        )

    @Test
    fun blankLibraryUsesSupportedHashHelpersOnActualV8(): Unit = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        val result =
            withTimeout(60_000) {
                DartSourceEngine.evaluate(
                    source("hash").copy(jsLib = " "),
                    "({sha:java.digestHex('abc','SHA-256'),md5:java.md5Encode('abc')})",
                ) as Map<*, *>
            }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            result["sha"],
        )
        assertEquals("900150983cd24fb0d6963f7d28e17f72", result["md5"])
    }

    private suspend fun assertModernLibraryMigrationRequiresReview(library: String, suffix: String): BookSource {
        val original = source(suffix).copy(jsLib = library)
        val preview = withTimeout(60_000) { DartSourceEngine.migrate(original) }
        assertTrue(preview.requiresManualWork)
        assertFalse(preview.canApply)
        assertEquals("manualRequired", preview.status)
        assertTrue(preview.issues.any {
            it.path == "jsLib" && it.code == "legacy.capability_requires_review"
        })
        assertEquals(library, original.jsLib)
        return original
    }

    @Test
    fun explicitHostLibraryExecutesWithOriginalRuntimeBindingsWhileModernMigrationNeedsReview(): Unit = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        val original = assertModernLibraryMigrationRequiresReview(
            """
            function requestApiUrl(path,data,runtime) {
              return [typeof runtime.java,typeof runtime.java.log,
                typeof runtime.source,typeof runtime.cache].join('|');
            }
            """.trimIndent(),
            "java-host",
        )
        val cacheKey = "crypto-runtime-contract-${UUID.randomUUID()}"
        val other = source("java-host-other-${UUID.randomUUID()}").copy(jsLib = original.jsLib)
        val bindings = mapOf("fixtureCacheKey" to cacheKey)
        try {
            val result = withTimeout(60_000) {
                DartSourceEngine.evaluate(original, "requestApiUrl('/fixture',{},this)")
            }
            assertEquals("Actual explicit runtime bindings: $result", "object|function|object|object", result)
            val runtime = withTimeout(60_000) {
                DartSourceEngine.evaluate(original,
                    "cache.put(fixtureCacheKey,'native-cache-value');({key:source.getKey(),value:cache.get(fixtureCacheKey)})",
                    bindings)
            }
            assertTrue("Actual source/cache operations: $runtime", runtime is Map<*, *>)
            runtime as Map<*, *>
            assertEquals("Actual source/cache operations: $runtime", original.bookSourceUrl, runtime["key"])
            assertEquals("Actual source/cache operations: $runtime", "native-cache-value", runtime["value"])
            assertEquals("native-cache-value", withContext(Dispatchers.IO) { CacheManager.get(cacheKey) })
            // Original CacheManager uses a global key, not a per-source namespace.
            val shared = withTimeout(60_000) {
                DartSourceEngine.evaluate(other,
                    "({key:source.getKey(),value:cache.get(fixtureCacheKey)})", bindings)
            }
            assertTrue("Actual second source/global cache: $shared", shared is Map<*, *>)
            shared as Map<*, *>
            assertEquals("Actual second source/global cache: $shared", other.bookSourceUrl, shared["key"])
            assertEquals("Actual second source/global cache: $shared", "native-cache-value", shared["value"])
            assertNull(withTimeout(60_000) {
                DartSourceEngine.evaluate(original, "cache.delete(fixtureCacheKey);cache.get(fixtureCacheKey)", bindings)
            })
            assertNull(withTimeout(60_000) { DartSourceEngine.evaluate(other, "cache.get(fixtureCacheKey)", bindings) })
            assertNull(withContext(Dispatchers.IO) { CacheManager.get(cacheKey) })
            val reflection = withTimeout(60_000) {
                DartSourceEngine.evaluate(original, "({packages:typeof Packages,getClass:typeof getClass,bookReflection:typeof book.getClass})",
                    mapOf("book" to mapOf("name" to "JSON book")))
            }
            assertTrue("Actual JSON runtime boundary: $reflection", reflection is Map<*, *>)
            reflection as Map<*, *>
            assertEquals("Actual JSON runtime boundary: $reflection", "undefined", reflection["packages"])
            assertEquals("Actual JSON runtime boundary: $reflection", "undefined", reflection["getClass"])
            assertEquals("Actual JSON runtime boundary: $reflection", "undefined", reflection["bookReflection"])
        } finally {
            withContext(Dispatchers.IO) { CacheManager.delete(cacheKey) }
            DartSourceEngine.clearSourceState(original)
            DartSourceEngine.clearSourceState(other)
        }
    }

    @Test
    fun sharedLibraryDescriptorsAndFrozenGlobalsKeepTheirOriginalRuntimeContract(): Unit = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        val original = assertModernLibraryMigrationRequiresReview(
            """
            var pixivLibraryMarker='ready';
            globalThis.environment={IS_LEGADO:true};
            globalThis.settings={language:'zh-CN'};
            Object.defineProperty(globalThis,'accessorValue',{
              configurable:true,get:function(){return this===globalThis?7:-1;}
            });
            Object.defineProperty(globalThis,'setterValue',{
              configurable:true,set:function(value){globalThis.setterStored=value;}
            });
            globalThis.__defineGetter__(0,function(){return 10;});
            Object.preventExtensions(globalThis);
            """.trimIndent(),
            "descriptors",
        )
        try {
            val first = withTimeout(60_000) {
                DartSourceEngine.evaluate(original, """
                    (function(){
                      const getter=Object.getOwnPropertyDescriptor(globalThis,'accessorValue');
                      const setter=Object.getOwnPropertyDescriptor(globalThis,'setterValue');
                      let blockedError='';
                      try { Object.defineProperty(globalThis,'afterPreventExtensions',{value:true}); }
                      catch(error) { blockedError=error.name; }
                      globalThis.setterValue=11;
                      return {marker:pixivLibraryMarker,environment:globalThis.environment.IS_LEGADO,
                        language:globalThis.settings.language,getter:globalThis.accessorValue,index:globalThis[0],
                        getterType:typeof getter.get,setterType:typeof setter.set,
                        extensible:Object.isExtensible(globalThis),blockedError,
                        added:typeof globalThis.afterPreventExtensions,
                        setterStored:typeof globalThis.setterStored};
                    })()
                """.trimIndent())
            }
            assertTrue("Actual library descriptor probe: $first", first is Map<*, *>)
            first as Map<*, *>
            val detail = "Actual library descriptor probe: $first"
            assertEquals(detail, "ready", first["marker"])
            assertEquals(detail, true, first["environment"])
            assertEquals(detail, "zh-CN", first["language"])
            assertEquals(detail, 7, (first["getter"] as? Number)?.toInt())
            assertEquals(detail, 10, (first["index"] as? Number)?.toInt())
            assertEquals(detail, "function", first["getterType"])
            assertEquals(detail, "function", first["setterType"])
            assertEquals(detail, false, first["extensible"])
            assertEquals(detail, "TypeError", first["blockedError"])
            assertEquals(detail, "undefined", first["added"])
            // The setter cannot create a new property after preventExtensions.
            assertEquals(detail, "undefined", first["setterStored"])
            val second = withTimeout(60_000) {
                DartSourceEngine.evaluate(original, "({getter:globalThis.accessorValue,index:globalThis[0],extensible:Object.isExtensible(globalThis)})")
            }
            assertTrue("Actual second retained library call: $second", second is Map<*, *>)
            second as Map<*, *>
            assertEquals("Actual second retained library call: $second", 7, (second["getter"] as? Number)?.toInt())
            assertEquals("Actual second retained library call: $second", 10, (second["index"] as? Number)?.toInt())
            assertEquals("Actual second retained library call: $second", false, second["extensible"])
        } finally {
            DartSourceEngine.clearSourceState(original)
        }
    }

    @Test
    fun retainedLibraryStateRefreshAndClearStayOwnerScopedWhileModernMigrationNeedsReview(): Unit = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        val original = assertModernLibraryMigrationRequiresReview(
            "var cleanupLibraryMarker=true;globalThis.sourceKind='book';",
            "library-refresh",
        )
        val other = source("other-library-owner").copy(jsLib = original.jsLib)
        try {
            assertEquals(true, withTimeout(60_000) {
                DartSourceEngine.evaluate(original, "globalThis.refreshMarker='first';cleanupLibraryMarker && globalThis.sourceKind==='book'")
            })
            assertEquals(true, withTimeout(60_000) {
                DartSourceEngine.evaluate(other, "globalThis.refreshMarker='second';cleanupLibraryMarker && globalThis.sourceKind==='book'")
            })
            assertEquals("first", withTimeout(60_000) { DartSourceEngine.evaluate(original, "globalThis.refreshMarker") })
            SharedJsScope.remove(original.jsLib)
            assertEquals("first", withTimeout(60_000) { DartSourceEngine.evaluate(original, "globalThis.refreshMarker") })
            assertEquals("second", withTimeout(60_000) { DartSourceEngine.evaluate(other, "globalThis.refreshMarker") })
            DartSourceEngine.clearSourceState(original)
            val refreshed = withTimeout(60_000) {
                DartSourceEngine.evaluate(original, "({marker:typeof globalThis.refreshMarker,library:cleanupLibraryMarker,kind:globalThis.sourceKind})")
            }
            assertTrue("Actual cleared owner state: $refreshed", refreshed is Map<*, *>)
            refreshed as Map<*, *>
            assertEquals("undefined", refreshed["marker"])
            assertEquals(true, refreshed["library"])
            assertEquals("book", refreshed["kind"])
            assertEquals("second", withTimeout(60_000) { DartSourceEngine.evaluate(other, "globalThis.refreshMarker") })
        } finally {
            DartSourceEngine.clearSourceState(original)
            DartSourceEngine.clearSourceState(other)
        }
    }

    @Test
    fun configurationUsesHashHelperAndJsonRuntimeWithoutJavaReflection(): Unit = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        val text =
            """
            const config={
              bookSourceUrl:'https://crypto-config.invalid',
              bookSourceName:java.md5Encode('abc'),
              bookSourceComment:[typeof Packages,typeof java,typeof getClass,
                typeof __legadoSecureRandomInt].join('|')
            };
            function search(key,page){return [];}
            function getChapters(book){return [];}
            function getContent(chapter){return '';}
            """
                .trimIndent()
        val imported =
            withTimeout(60_000) {
                withContext(Dispatchers.IO) { JsSourceConfig.extract(text) }
            }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", imported.bookSourceName)
        assertEquals("undefined|object|undefined|undefined", imported.bookSourceComment)
        assertEquals(text, imported.mainJs)
        val dto =
            withTimeout(60_000) {
                DartSourceEngine.evaluate(
                    source("dto"),
                    "({name:book.name,reflection:typeof book.getClass,packages:typeof Packages})",
                    mapOf("book" to mapOf("name" to "JSON book")),
                ) as Map<*, *>
            }
        assertEquals("JSON book", dto["name"])
        assertEquals("undefined", dto["reflection"])
        assertEquals("undefined", dto["packages"])
    }
}
