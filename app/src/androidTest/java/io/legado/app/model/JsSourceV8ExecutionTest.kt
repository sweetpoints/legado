package io.legado.app.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.BuildConfig
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isWebFile
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executable source assertions belong to the embedded V8 backend, not JVM V8. */
@RunWith(AndroidJUnit4::class)
class JsSourceV8ExecutionTest {
    private fun requireV8() = assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)

    private fun source(script: String, type: Int = 0) =
        BookSource(
            bookSourceUrl = "https://persisted.example",
            bookSourceName = "测试源",
            bookSourceType = type,
            mainJs = script,
        )

    private suspend fun search(script: String, input: Map<String, Any?> = emptyMap()) =
        DartSourceEngine.execute(source(script), "search", input).single()

    @Test
    fun runtimeSourceRemainsAvailableWithConfigObject() = runBlocking {
        requireV8()
        val result =
            search(
                """
                var config={bookSourceUrl:'https://config.example'};
                function identity(){return {configUrl:config.bookSourceUrl,runtimeUrl:source.bookSourceUrl,aliasUrl:sourceApi.bookSourceUrl};}
                function search(){return [identity()];}
                """
                    .trimIndent()
            )
        assertEquals("https://config.example", result["configUrl"])
        assertEquals("https://persisted.example", result["runtimeUrl"])
        assertEquals("https://persisted.example", result["aliasUrl"])
    }

    @Test
    fun callsMainJsSearchThroughCompleteV8Engine() = runBlocking {
        requireV8()
        val result =
            search(
                """
                var source={bookSourceUrl:'https://script.example'};
                function search(key,page){return [{name:key,bookUrl:source.bookSourceUrl+'/book/'+page}];}
                """
                    .trimIndent(),
                mapOf("key" to "书名", "page" to 3),
            )
        assertEquals("书名", result["name"])
        assertEquals("https://script.example/book/3", result["bookUrl"])
    }

    @Test
    fun constForInAndBranchScopesExecuteInV8() = runBlocking {
        requireV8()
        val result =
            search(
                """
                function normalize(params){const output={};for(const key in params)output[key]=String(params[key]);
                  if(String(params.mode)==='free'){const result='free';output.result=result;}else{const result='paid';output.result=result;}return output;}
                function search(key,page){return [normalize({page:page,mode:key})];}
                """
                    .trimIndent(),
                mapOf("key" to "free", "page" to 3),
            )
        assertEquals("3", result["page"])
        assertEquals("free", result["result"])
    }

    @Test
    fun persistedSourceApiSurvivesScriptSourceShadowing() = runBlocking {
        requireV8()
        val result =
            search(
                """
                var source={bookSourceUrl:'https://script.example'};
                function identity(){return {configUrl:source.bookSourceUrl,persistedUrl:sourceApi.bookSourceUrl};}
                function search(){return [identity()];}
                """
                    .trimIndent()
            )
        assertEquals("https://script.example", result["configUrl"])
        assertEquals("https://persisted.example", result["persistedUrl"])
    }

    @Test
    fun customToJsonAndGetterRunInV8SourceScope() = runBlocking {
        requireV8()
        val result =
            DartSourceEngine.evaluate(
                source(""),
                """
                (function(){var prefix='scope';var result={};
                  Object.defineProperty(result,'computed',{enumerable:true,get:function(){return prefix+'-getter';}});
                  result.toJSON=function(){return {transformed:this.computed};};return result;})()
                """
                    .trimIndent(),
            ) as Map<*, *>
        assertEquals("scope-getter", result["transformed"])
    }

    @Test
    fun optionalMissingFunctionProducesNullInActualV8() = runBlocking {
        requireV8()
        assertNull(
            DartSourceEngine.evaluate(
                source(""),
                "(function(){var source={};return typeof getBookInfo==='function'?getBookInfo():null;})()",
            )
        )
    }

    @Test
    fun optionalPresentFunctionRunsInActualV8() = runBlocking {
        requireV8()
        val result =
            DartSourceEngine.evaluate(
                source(""),
                "(function(){function getBookInfo(){return {intro:'optional result'};}return typeof getBookInfo==='function'?getBookInfo():null;})()",
            ) as Map<*, *>
        assertEquals("optional result", result["intro"])
    }

    @Test
    fun volumePlaceholderSkipsContentAndIgnoresTag() = runBlocking {
        requireV8()
        assertEquals(
            "",
            WebBook.getContentAwait(
                source("function getContent(){throw 'must not run';}"),
                Book(bookUrl = "https://persisted.example/book", name = "Book"),
                BookChapter(
                    url = "Volume 1#0",
                    title = "Volume 1",
                    isVolume = true,
                    tag = "2026-08-10",
                ),
                needSave = false,
            ),
        )
    }

    @Test
    fun blankContentAllowedOnlyForVolumeChapters() = runBlocking {
        requireV8()
        val definition = source("function getContent(){return '';}")
        val book = Book(bookUrl = "https://persisted.example/book", name = "Book")
        val chapter =
            BookChapter(
                url = "https://persisted.example/volume",
                title = "Volume 1",
                isVolume = true,
            )
        assertEquals("", WebBook.getContentAwait(definition, book, chapter, needSave = false))
        assertThrows(ContentEmptyException::class.java) {
            runBlocking {
                WebBook.getContentAwait(
                    definition,
                    book,
                    chapter.copy(isVolume = false),
                    needSave = false,
                )
            }
        }
        Unit
    }

    @Test
    fun fileSourceResolvesDownloadsWithoutTocFallback() = runBlocking {
        requireV8()
        val book = Book(bookUrl = "https://persisted.example/detail/1", name = "书名")
        WebBook.getBookInfoAwait(
            source(
                "function getBookInfo(book){return {type:8,downloadUrls:['/download/book.epub']};}",
                BookSourceType.file,
            ),
            book,
            true,
        )
        assertEquals(listOf("https://persisted.example/download/book.epub"), book.downloadUrls)
        assertTrue(book.tocUrl.isBlank())
        assertTrue(book.isWebFile)
    }

    @Test
    fun fileSourceRejectsEmptyDownloadUrls() = runBlocking {
        requireV8()
        val error =
            assertThrows(NoStackTraceException::class.java) {
                runBlocking {
                    WebBook.getBookInfoAwait(
                        source("function getBookInfo(book){return {};}", BookSourceType.file),
                        Book(bookUrl = "https://persisted.example/detail/1", name = "书名"),
                        true,
                    )
                }
            }
        assertTrue(error.message.orEmpty().contains("下载链接为空"))
        Unit
    }

    private fun asset(path: String) =
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .assets
            .open(path)
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun builtInTemplateRemainsImportableInActualV8() = runBlocking {
        requireV8()
        val script = asset("js_source_template.js")
        val imported = withContext(Dispatchers.IO) { JsSourceConfig.extract(script) }
        assertTrue(script.contains("var config ="))
        assertEquals("https://example.com", imported.bookSourceUrl)
        assertEquals("示例 JS 书源", imported.bookSourceName)
        assertEquals(script, imported.mainJs)
    }

    @Test
    fun documentedExampleRemainsImportableInActualV8() = runBlocking {
        requireV8()
        val guide = asset("web/help/md/jsHelp.md")
        val match =
            Regex(
                    """<!-- js-source-example:start -->\s*```js\s*(.*?)\s*```\s*<!-- js-source-example:end -->""",
                    RegexOption.DOT_MATCHES_ALL,
                )
                .find(guide)
        assertNotNull("Missing JavaScript source example markers", match)
        val script = match!!.groupValues[1].trim()
        val imported = withContext(Dispatchers.IO) { JsSourceConfig.extract(script) }
        assertTrue(script.contains("var config ="))
        assertEquals("https://example.com", imported.bookSourceUrl)
        assertEquals("示例 JS 书源", imported.bookSourceName)
        assertTrue(imported.loginUi.orEmpty().contains("账号"))
        assertTrue(imported.exploreUrl.orEmpty().contains("分类"))
        assertEquals(script, imported.mainJs)
    }

    @Test
    fun infiniteConfigurationSaveTimesOutBeforeDatabaseChanges() = runBlocking {
        requireV8()
        val before = withContext(Dispatchers.IO) { appDb.bookSourceDao.allCount() }
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                JsSourceUpsert.save("while (true) {}", timeoutMillis = 100)
            }
        }
        assertEquals(before, withContext(Dispatchers.IO) { appDb.bookSourceDao.allCount() })
        Unit
    }
}
