package io.legado.app.model.jsSource

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceDeclarationsTest {
    private val pair = setOf("getReviewSummary", "getReviewDetail")
    private fun detects(script: String) = JsSourceDeclarations.declares(script, pair)
    @Test fun asyncDeclarationsAndModernBodiesAreRecognizedWithoutExecution() {
        assertTrue(detects("async function getReviewSummary(){return await Promise.resolve([]);} async function getReviewDetail(){return config?.value ?? [];}"))
        assertTrue(detects("const getReviewSummary=async()=>[]; const getReviewDetail=async (chapter, book)=>({items:[]});"))
        assertTrue(detects("var getReviewSummary=async function(){};let getReviewDetail=function named(){};"))
        assertTrue(detects("function* getReviewSummary(){} async function* getReviewDetail(){}"))
    }
    @Test fun stringsCommentsRegexAndTemplatesDoNotDeclareCapabilities() {
        for (script in listOf(
            "/* async function getReviewSummary(){} async function getReviewDetail(){} */",
            "// function getReviewSummary(){}\n// function getReviewDetail(){}",
            "const s='function getReviewSummary(){} function getReviewDetail(){}';",
            "const r=/function getReviewSummary(){} function getReviewDetail(){}/;",
            "const t=`function getReviewSummary(){} function getReviewDetail(){}`;",
            "const t=`\${(()=>{function getReviewSummary(){} function getReviewDetail(){}})()}`;",
            "const obj={async getReviewSummary(){},getReviewDetail(){}};",
            "function container(){function getReviewSummary(){} function getReviewDetail(){}}",
            "if(false){function getReviewSummary(){} function getReviewDetail(){}}",
        )) assertFalse(script, detects(script))
    }
    @Test fun topLevelBindingsAndShadowingAreDistinguished() {
        val functions="function getReviewSummary(){} function getReviewDetail(){};"
        assertFalse(detects(functions+"getReviewSummary=3;"))
        assertFalse(detects("var alias=function getReviewSummary(){};function getReviewDetail(){}"))
        assertFalse(detects("const getReviewSummary=(function(){return 3;})();function getReviewDetail(){}"))
        assertTrue(detects(functions+"function nested(){let getReviewSummary=3;}"))
        assertTrue(detects(functions+"obj.getReviewSummary=3;"))
        assertTrue(detects(functions+"const nested=()=>getReviewSummary=3;"))
        assertTrue(detects("let getReviewSummary=3;getReviewSummary=async ()=>[];function getReviewDetail(){}"))
    }
    @Test fun nestedTemplatesRegexAndCommentsInsideAsyncBodyKeepTopLevelScope() {
        assertTrue(detects("""
            async function getReviewSummary(){const r=/[{}]/; return `${'$'}{`nested ${'$'}{({text:'}'}).text}`}`;}
            async function getReviewDetail(){/* } function fake(){} */ return await Promise.resolve([]);}
        """.trimIndent()))
        assertFalse(detects("async function getReviewSummary(){"))
        assertFalse(detects("const t=`unterminated;function getReviewSummary(){} function getReviewDetail(){}"))
    }
    @Test fun asyncSourcesStampOnlyDirectLiteralConfigTimestamps() {
        val script="const config={lastUpdateTime:Date.now(),nested:{lastUpdateTime:9}}; async function getReviewSummary(){}"
        val ranges=JsSourceDeclarations.timestampRanges(script)
        assertTrue(ranges.size == 1)
        val result=ranges.sortedByDescending { it.first }.fold(script) { value, range -> value.replaceRange(range,"123456") }
        org.junit.Assert.assertEquals("const config={lastUpdateTime:123456,nested:{lastUpdateTime:9}}; async function getReviewSummary(){}",result)
        val source="var source={'lastUpdateTime':42}; const f=async()=>[];"
        val range=JsSourceDeclarations.timestampRanges(source).single()
        org.junit.Assert.assertEquals("var source={'lastUpdateTime':0}; const f=async()=>[];",source.replaceRange(range,"0"))
    }
    @Test fun timestampFallbackDoesNotEditStringsCommentsDynamicOrNestedConfigs() {
        for (script in listOf(
            "const config={lastUpdateTime:other.now()};async function f(){}",
            "const config={lastUpdateTime:1+2};async function f(){}",
            "function outer(){const config={lastUpdateTime:42};} async function f(){}",
            "const text='var config={lastUpdateTime:42}';async function f(){}",
            "/*var config={lastUpdateTime:42}*/async function f(){}",
            "const config={['lastUpdateTime']:42};async function f(){}",
        )) assertTrue(script,JsSourceDeclarations.timestampRanges(script).isEmpty())
    }

}
