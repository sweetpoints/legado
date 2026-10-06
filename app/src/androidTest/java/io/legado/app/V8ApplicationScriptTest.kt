package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskProtocol
import io.legado.app.model.replace.ReplacePreview
import io.legado.app.model.replace.ReplacePreviewException
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.V8ScriptExecutor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Application scripts run through the shipped channel and actual native V8. */
@RunWith(AndroidJUnit4::class)
class V8ApplicationScriptTest {
    private suspend fun evaluate(script: String, bindings: Map<String, Any?> = emptyMap()) =
        V8ScriptExecutor.evaluate(script, bindings)

    @Test
    fun jsonBindingsPreserveMapChapterAndArrayValues() = runBlocking {
        assertEquals(
            "3242532321",
            evaluate("result.id", mapOf("result" to mapOf("id" to "3242532321"))),
        )
        assertEquals(
            "xxxyyy",
            evaluate("chapter.title", mapOf("chapter" to mapOf("title" to "xxxyyy"))),
        )
        assertEquals(
            6,
            (evaluate(
                    "var sum=0; list.forEach(item=>sum+=item); sum",
                    mapOf("list" to listOf(1, 2, 3)),
                )
                    as Number)
                .toInt(),
        )
        assertEquals(
            "12012",
            evaluate(
                "var result=0;var a=[1,2,3];for(let i=0;i<a.length;i++)result+=a[i];for(let item of a)result+=item;for(let key in a)result+=key;result"
            ),
        )
        assertNull(evaluate("null"))
        assertNull(evaluate("undefined"))
    }

    @Test
    fun unicodeChapterRegularExpressionAndNativeStringsKeepTheirMeaning() = runBlocking {
        assertEquals(
            "第七百一十四章 人头树",
            evaluate(
                "var s=result.match(/(.{1,6}?)(第.*)/);var n=s[2].length-parseInt(6-s[1].length);s[2].substr(0,n)",
                mapOf("result" to "筳彩涫第七百一十四章 人头树鮺舦綸"),
            ),
        )
        val bindings =
            mapOf("chapter" to mapOf("title" to "第1章", "tag" to "", "url" to "https://a/b/"))
        assertEquals(
            "string:number:3:F:true:https://a/X/:5",
            evaluate(
                "[typeof chapter.title,typeof chapter.title.length,chapter.title.length,chapter.tag?'T':'F',chapter.title==='第1章',chapter.url.replace(/b/,'X'),chapter.url.split('/').length].join(':')",
                bindings,
            ),
        )
    }

    @Test
    fun stableSyntaxAndLexicalScopesFollowV8() = runBlocking {
        val cases =
            listOf(
                "const suffix='ok'; `v8-${'$'}{suffix}`" to "v8-ok",
                "var {left,right}={left:2,right:3};var [first,second]=[4,5];''+(left+right+first+second)" to
                    "14",
                "function valueOr(value=7){return value};''+valueOr()" to "7",
                "var config={nested:{value:5}};''+(config.missing?.value??config.nested?.value)" to
                    "5",
                "var key='n';var value={[key]:4,double(){return this[key]*2}};''+value.double()" to
                    "8",
                "const params={first:1,second:2};const values=[];for(const key in params)values.push(key+':'+params[key]);if(params.first===1){const result='left';values.push(result)}else{const result='right';values.push(result)}values.join('|')" to
                    "first:1|second:2|left",
                "function add(...items){return items.reduce((a,b)=>a+b,0)};''+add(...[1,2,3])" to
                    "6",
                "var count=0;var a=0,b=null,c=3;a||=++count;b??=++count;c&&=++count;[a,b,c,count].join(':')" to
                    "1:2:3:3",
                "var value='outer';{const value='inner'}value" to "outer",
                "try{eval('const broken = ;')}catch(error){error.name}" to "SyntaxError",
            )
        for ((script, expected) in cases) assertEquals(script, expected, evaluate(script))
        for (declaration in listOf("var", "let", "const")) assertEquals(
            1,
            (evaluate("$declaration config={value:1};config.value") as Number).toInt(),
        )
    }

    @Test
    fun dynamicEvaluationUsesCurrentBindingsAndStandardLexicalLifetime() = runBlocking {
        val bindings = mapOf("runtimeValue" to "EXEC_ENV")
        assertEquals("EXEC_ENV", evaluate("(0,eval)('runtimeValue')", bindings))
        assertEquals("EXEC_ENV", evaluate("new Function('return runtimeValue')()", bindings))
        assertEquals(
            "EXEC_ENV-L",
            evaluate("function f(){var local='-L';return eval('runtimeValue+local')}f()", bindings),
        )
        assertEquals(
            "fn:ok",
            evaluate("eval(\"function gzip(value){return 'fn:'+value}\");gzip('ok')"),
        )
        assertEquals(
            "var:ok",
            evaluate(
                "eval(\"var gzip=function(value){return 'var:'+value}\");eval(\"gzip('ok')\")"
            ),
        )
        assertEquals("undefined", evaluate("eval(\"let lexical='value'\");typeof lexical"))
        assertEquals("undefined", evaluate("with({}){const scoped='value'}typeof scoped"))
    }

    @Test
    fun removedJvmClassLoadingAndE4xCannotExecute() = runBlocking {
        for (script in
            listOf(
                "new JavaImporter(Packages.dalvik.system.DexClassLoader)",
                "new Packages.io.legado.app.api.ReturnData()",
                "var data=<root><item>ok</item></root>;String(data..item)",
            )) {
            val outcome = runCatching { evaluate(script) }
            assertTrue("Unsupported executable must fail: $script", outcome.isFailure)
        }
    }

    @Test
    fun sourceElementFacadePreservesAttributeTextAndListObservations() = runBlocking {
        val source =
            BookSource(bookSourceUrl = "https://v8-elements.invalid", bookSourceName = "elements")
        val html = "<div id='video-artist-name'><a href='/artist/1'>n</a></div>"
        val result =
            DartSourceEngine.evaluate(
                source,
                "var nodes=java.getElements('#video-artist-name a');[nodes.attr('href'),nodes.text(),nodes.size(),nodes.first().outerHtml().includes('href')].join('|')",
                mapOf("result" to html),
            )
        assertEquals("/artist/1|n|1|true", result)
        assertTrue(
            runCatching {
                DartSourceEngine.evaluate(
                    source,
                    "java.getElements('#video-artist-name a').html()",
                    mapOf("result" to html),
                )
            }
                .isFailure
        )
    }

    @Test
    fun customToJsonAndConstructedProtocolAreConvertedByActualV8() = runBlocking {
        val value =
            evaluate(
                "var n=1;[{type:'notify',title:'Task '+n,toJSON(){return {type:this.type,title:this.title}}}]"
            )
        assertEquals("Task 1", AutoTaskProtocol.parseActions(value)?.single()?.get("title"))
        val book =
            Book(
                bookUrl = "https://example.com/book?value=\");throw new Error('bad');//",
                name = "Test",
                author = "Author",
            )
        val task = AutoTask.buildBookUpdateTask(book, "Update Test")
        val action = AutoTaskProtocol.parseActions(evaluate(task.script))!!.single()
        assertEquals(book.bookUrl, action["bookUrl"])
        assertEquals(book.name, action["bookName"])
        assertEquals(book.author, action["bookAuthor"])
        assertEquals("refreshToc", action["type"])
        assertEquals(AutoTask.BOOK_UPDATE_GENERATOR, action["generatedBy"])
    }

    @Test
    fun bundledFormatterExecutesOfflineInActualV8() = runBlocking {
        val runtime =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .assets
                .open("scripts/beautify.min.js")
                .bufferedReader()
                .use { it.readText() }
        assertEquals(
            "function demo() {\n    return 1;\n}",
            evaluate(
                "var window={};\n$runtime\nwindow.js_beautify('function demo(){return 1;}',{indent_size:4});"
            ),
        )
    }

    @Test
    fun parallelPromisesAndDispatcherHandoffsRemainIsolated() = runBlocking {
        val results = coroutineScope {
            (0 until 12)
                .map { value ->
                    async(Dispatchers.Default) {
                        withContext(Dispatchers.IO) {
                            evaluate("Promise.resolve(value+'-resumed')", mapOf("value" to value))
                        }
                    }
                }
                .awaitAll()
        }
        assertEquals((0 until 12).map { "$it-resumed" }, results)
    }

    @Test
    fun cancellationInterruptsNativeLoopAndLeavesNextRequestUsable() = runBlocking {
        val failure = runCatching {
            withTimeout(150) { evaluate("while(true){}") }
        }
            .exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("healthy", evaluate("'healthy'"))
    }

    @Test
    fun headerWrappersExecuteThroughTheProductionSourceInActualV8() = runBlocking {
        withContext(Dispatchers.IO) {
            val source =
                RssSource(
                    sourceUrl = "https://headers-${java.util.UUID.randomUUID()}.invalid",
                    sourceName = "header-test",
                )
            source.header = "\n <JS>({ 'X-Test':'tag','X-Base':baseUrl })</JS>\t"
            assertEquals("tag", source.getHeaderMap()["X-Test"])
            assertEquals(source.sourceUrl, source.getHeaderMap()["X-Base"])
            source.header = " \t@JS:({ 'X-Test':'prefix' })\n"
            assertEquals("prefix", source.getHeaderMap()["X-Test"])
        }
    }

    @Test
    fun dynamicLoginDefaultsExecuteOnceAndPersistTheirDecodedValue() = runBlocking {
        withContext(Dispatchers.IO) {
            val source =
                RssSource(
                    sourceUrl = "https://login-default-${java.util.UUID.randomUUID()}.invalid",
                    sourceName = "login-test",
                    loginUi = "@js:[{name:'token',type:'text',default:'v8-'+'default'}]",
                )
            try {
                assertEquals("v8-default", source.getLoginInfoMap()["token"])
                assertTrue(source.getLoginInfo().orEmpty().contains("v8-default"))
                source.loginUi = "@js:throw new Error('Stored lookup must not evaluate again')"
                assertEquals("v8-default", source.getLoginInfoMap()["token"])
            } finally {
                source.removeLoginInfo()
            }
        }
    }

    @Test
    fun regexPreviewCallsHostHelpersAndPreservesStateAcrossMatches() = runBlocking {
        val name = "v8-preview-${java.util.UUID.randomUUID()}"
        val rule =
            ReplaceRule(
                name = name,
                isRegex = true,
                pattern = "繁體",
                replacement =
                    "@js:java.put('seen',String((Number(java.get('seen'))||0)+1));java.t2s(result)+':'+java.get('seen')",
            )
        assertEquals("繁体:1 繁体:2", ReplacePreview.apply(rule, "繁體 繁體"))
    }

    @Test
    fun regexPreviewNativeLoopHitsRuleTimeoutAndCallerCancellation() = runBlocking {
        val rule =
            ReplaceRule(
                name = "v8-timeout",
                isRegex = true,
                pattern = "x",
                replacement = "@js:while(true){}",
                timeoutMillisecond = 150,
            )
        val timeout = runCatching { ReplacePreview.apply(rule, "x") }.exceptionOrNull()
        assertTrue(timeout is ReplacePreviewException)
        val cancelled = runCatching {
            withTimeout(100) { ReplacePreview.apply(rule.copy(timeoutMillisecond = 3000), "x") }
        }
            .exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }

    @Test
    fun syntaxValidationDoesNotExecuteAndReportsSourcePosition() = runBlocking {
        assertNull(V8ScriptExecutor.checkSyntax("while(true){}"))
        val diagnostic = V8ScriptExecutor.checkSyntax("var prefix='ok';\nvar broken=;")
        assertNotNull(diagnostic)
        assertEquals(2, diagnostic!!.lineNumber)
        assertTrue(diagnostic.columnNumber > 0)
        assertTrue(diagnostic.message.isNotBlank())
    }

    @Test
    fun runtimeErrorsReachCallerAndNestedErrorsRetainTheirMessage() = runBlocking {
        val thrown = runCatching {
            evaluate("var prefix='ok';\nthrow new Error('boom');")
        }
            .exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown!!.message.orEmpty().contains("boom"))
        val nested = runCatching {
            evaluate(
                "function inner(){var value=null;return value.missing()}function outer(){return inner()}outer()"
            )
        }
            .exceptionOrNull()
        assertNotNull(nested)
        assertTrue(nested!!.message.orEmpty().contains("missing"))
    }
}
