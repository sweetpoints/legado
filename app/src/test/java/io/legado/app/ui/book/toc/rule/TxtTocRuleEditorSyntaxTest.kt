package io.legado.app.ui.book.toc.rule

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.legado.app.data.entities.TxtTocRule
import org.junit.Assert.*
import org.junit.Test

class TxtTocRuleEditorSyntaxTest {
    @Test fun syntaxPreservesTextAndOffsetsWhileHighlightingJsonAndJavascript() {
        val input = AnnotatedString("{\"key\":\"value\"}\nreturn result + 1")
        val output = TxtTocRuleEditorSyntax(Color.Red, Color.Blue, Color.Gray, Color.Cyan).filter(input)
        assertEquals(input.text, output.text.text); assertTrue(output.text.spanStyles.isNotEmpty())
        input.text.indices.forEach { assertEquals(it, output.offsetMapping.originalToTransformed(it)); assertEquals(it, output.offsetMapping.transformedToOriginal(it)) }
        val start = input.text.indexOf("return")
        assertTrue(output.text.spanStyles.any { it.start == start && it.end == start + 6 && it.item.color == Color.Cyan })
    }
    @Test fun longReplacementKeepsTextWithoutExpensiveHighlighting() {
        val input = AnnotatedString("return result;".repeat(1000))
        val output = TxtTocRuleEditorSyntax(Color.Red, Color.Blue, Color.Gray, Color.Cyan).filter(input)
        assertEquals(input, output.text); assertTrue(output.text.spanStyles.isEmpty())
    }
    @Test fun parentCallbackTakesPriorityAndActivityIsFallback() {
        var parentCalls = 0; var activityCalls = 0
        val parent = object : TxtTocRuleEditDialog.Callback { override fun saveTxtTocRule(txtTocRule: TxtTocRule) { parentCalls++ } }
        val activity = object : TxtTocRuleEditDialog.Callback { override fun saveTxtTocRule(txtTocRule: TxtTocRule) { activityCalls++ } }
        val rule = TxtTocRule(id = 123)
        txtTocRuleEditorCallback(parent, activity)?.saveTxtTocRule(rule)
        assertEquals(1, parentCalls); assertEquals(0, activityCalls)
        txtTocRuleEditorCallback(Any(), activity)?.saveTxtTocRule(rule)
        assertEquals(1, activityCalls); assertNull(txtTocRuleEditorCallback(null, Any()))
    }
}
