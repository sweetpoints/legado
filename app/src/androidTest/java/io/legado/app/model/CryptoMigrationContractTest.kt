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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** New V8 migration contracts; this does not establish full CryptoJS compatibility. */
@RunWith(AndroidJUnit4::class)
class CryptoMigrationContractTest {
    private fun source(suffix: String) = BookSource(
        bookSourceUrl = "https://crypto-contract.invalid/$suffix",
        bookSourceName = "Crypto migration",
    )

    @Test
    fun blankLibraryUsesSupportedHashHelpersOnActualV8() = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        val result = withTimeout(60_000) {
            DartSourceEngine.evaluate(
                source("hash").copy(jsLib = " "),
                "({sha:java.digestHex('abc','SHA-256'),md5:java.md5Encode('abc')})",
            ) as Map<*, *>
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result["sha"])
        assertEquals("900150983cd24fb0d6963f7d28e17f72", result["md5"])
    }

    private suspend fun requiresLibraryMigration(library: String, suffix: String) {
        val original = source(suffix).copy(jsLib = library)
        val preview = withTimeout(60_000) { DartSourceEngine.migrate(original) }
        assertTrue(preview.requiresManualWork)
        assertFalse(preview.canApply)
        assertEquals("manualRequired", preview.status)
        assertTrue(preview.issues.any { it.path == "jsLib" && it.code == "legacy.capability_requires_review" })
        assertEquals(library, original.jsLib)
        val failure = withTimeout(60_000) {
            runCatching { DartSourceEngine.evaluate(original, "'must not run unsupported library'") }
        }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("legacy_requires_migration"))
    }

    @Test
    fun explicitJavaHostLibraryRequiresMigrationInsteadOfRhinoFallback() = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        requiresLibraryMigration(
            """
            function requestApiUrl(path,data,runtime) {
              return [typeof runtime.java,typeof runtime.java.log,
                typeof runtime.source,typeof runtime.cache].join('|');
            }
            """.trimIndent(),
            "java-host",
        )
    }

    @Test
    fun sharedGlobalsAndDescriptorsRemainExplicitMigrationInputs() = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        requiresLibraryMigration(
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
    }

    @Test
    fun oldLibraryRefreshStateRequiresMigration() = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        requiresLibraryMigration(
            "var cleanupLibraryMarker=true;globalThis.sourceKind='book';",
            "library-refresh",
        )
    }

    @Test
    fun configurationUsesHashHelperAndJsonRuntimeWithoutJavaReflection() = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        val text = """
            const config={
              bookSourceUrl:'https://crypto-config.invalid',
              bookSourceName:java.md5Encode('abc'),
              bookSourceComment:[typeof Packages,typeof java,typeof getClass,
                typeof __legadoSecureRandomInt].join('|')
            };
            function search(key,page){return [];}
            function getChapters(book){return [];}
            function getContent(chapter){return '';}
        """.trimIndent()
        val imported = withTimeout(60_000) {
            withContext(Dispatchers.IO) { JsSourceConfig.extract(text) }
        }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", imported.bookSourceName)
        assertEquals("undefined|object|undefined|undefined", imported.bookSourceComment)
        assertEquals(text, imported.mainJs)
        val dto = withTimeout(60_000) {
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
