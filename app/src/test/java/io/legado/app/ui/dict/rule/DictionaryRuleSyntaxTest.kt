package io.legado.app.ui.dict.rule

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test

class DictionaryRuleSyntaxTest {
    @Test
    fun legadoJsonAndJavaScriptPatternsKeepTextAndOffsets() {
        val syntax = DictionaryRuleSyntax(Color.Red, Color.Blue, Color.Gray, Color.Cyan)
        val text = "@js: const data = {\"name\":true}; return data"
        val transformed = syntax.filter(AnnotatedString(text))
        assertEquals(text, transformed.text.text)
        assertEquals(4, transformed.offsetMapping.originalToTransformed(4))
        assertTrue(
            transformed.text.spanStyles.any {
                text.substring(it.start, it.end) == "@js:" && it.item.color == Color.Red
            }
        )
        assertTrue(
            transformed.text.spanStyles.any {
                text.substring(it.start, it.end) == "const" && it.item.color == Color.Cyan
            }
        )
        assertTrue(transformed.text.spanStyles.any { it.item.color == Color.Blue })
    }
}
