package io.legado.app.ui.dict.rule

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import io.legado.app.ui.widget.code.*

/** Same Legado, JSON and JavaScript token patterns as the previous rule CodeView. */
class DictionaryRuleSyntax(orange: Color, blue: Color, grey: Color, lightBlue: Color) : VisualTransformation {
    private val patterns = listOf(legadoPattern to orange, jsonPattern to blue, wrapPattern to grey,
        operationPattern to orange, jsPattern to lightBlue)
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        patterns.forEach { (pattern, color) ->
            val matcher = pattern.matcher(text.text)
            while (matcher.find()) builder.addStyle(SpanStyle(color = color), matcher.start(), matcher.end())
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
