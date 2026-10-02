package io.legado.app.ui.book.toc.rule

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.*
import io.legado.app.model.analyzeRule.*

/** Replacement CodeView's original JSON/JavaScript patterns, without modifying editable text. */
class TxtTocRuleEditorSyntax(orange: Color, blue: Color, grey: Color, lightBlue: Color) : VisualTransformation {
    private val patterns = listOf(jsonPattern to blue, wrapPattern to grey, operationPattern to orange, jsPattern to lightBlue)
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > 4096) return TransformedText(text, OffsetMapping.Identity)
        val result = AnnotatedString.Builder(text)
        patterns.forEach { (pattern, color) -> val matcher = pattern.matcher(text.text)
            while (matcher.find()) result.addStyle(SpanStyle(color = color), matcher.start(), matcher.end()) }
        return TransformedText(result.toAnnotatedString(), OffsetMapping.Identity)
    }
}
