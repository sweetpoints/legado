package io.legado.app.model.jsSource

import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceEngineTest {

    @Test
    fun `normalizes object and lazy strings with current rhino`() {
        val (result, _) = evaluate(
            "function search(key, page) { return [{name: key, bookUrl: 'u' + page}]; }",
            "search(key, page)",
            listOf("key" to "测试", "page" to 2),
        )

        val json = JsSourceEngine.normalizeJsResult(result).orEmpty()
        assertTrue(json.contains("测试"))
        assertTrue(json.contains("u2"))
    }

    @Test
    fun `preserves cancellation while normalizing custom toJSON`() {
        val (result, _) = evaluate(
            "function value() { return { toJSON: function() { return { ok: true }; } }; }",
            "value()",
        )
        val job = Job().apply { cancel() }

        assertThrows(CancellationException::class.java) {
            JsSourceEngine.normalizeJsResult(result, job)
        }
    }

    @Test
    fun `passes content string through unchanged`() {
        val (result, _) = evaluate(
            "function content() { return '第一段\\n第二段'; }",
            "content()",
        )

        assertEquals("第一段\n第二段", JsSourceEngine.normalizeJsResult(result))
    }

    @Test
    fun `maps undefined to null`() {
        val (result, _) = evaluate("function noop() {}", "noop()")
        assertNull(JsSourceEngine.normalizeJsResult(result))
    }

    private fun evaluate(
        script: String,
        expression: String,
        args: List<Pair<String, Any?>> = emptyList(),
    ): Pair<Any?, ScriptBindings> {
        val bindings = buildScriptBindings { target ->
            args.forEach { (key, value) -> target[key] = value }
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        RhinoScriptEngine.eval(script, scope)
        return RhinoScriptEngine.eval(expression, scope) to scope
    }
}
