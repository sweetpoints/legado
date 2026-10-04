package io.legado.app.ui.widget.dialog

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CodeSyntaxProjectionTest {
    private val colors =
        CodeSyntaxColors(Color.Red, Color.Blue, Color.Gray, Color.Green, Color.Cyan)

    @Test
    fun actualPatternsRetainAllTextAndUtf16OffsetsForEmojiAndMultiline() = runTest {
        val input = "😀 @js: const value = {\"key\": true};\nreturn value;"
        val projected = projectCodeSyntax(input, colors)
        assertEquals(input, projected.text)
        val keyword = input.indexOf("const")
        assertTrue(
            projected.spanStyles.any {
                it.start == keyword && it.end == keyword + 5 && it.item.color == Color.Cyan
            }
        )
        val rule = input.indexOf("@js:")
        assertTrue(
            projected.spanStyles.any {
                it.start == rule && it.end == rule + 4 && it.item.color == Color.Red
            }
        )
        assertTrue(
            projected.spanStyles.all { it.start in 0..input.length && it.end in 0..input.length }
        )
    }

    @Test
    fun plainTextAndEmptyDocumentsHaveNoInventedSyntaxOrChangedOffsets() = runTest {
        listOf("", "plain 中文 😀").forEach { input ->
            val projected = projectCodeSyntax(input, colors)
            assertEquals(input, projected.text)
            assertTrue(projected.spanStyles.isEmpty())
        }
    }

    @Test
    fun viewportStylingPreservesFullDocumentAndAbsoluteUtf16Offsets() = runTest {
        val input = "😀 var first = true;\n" + "plain ".repeat(1000) + "const last = false;"
        val start = input.indexOf("const")
        val end = input.length
        val projected = projectCodeSyntax(input, colors, start until end)
        assertEquals(input, projected.text)
        assertTrue(projected.spanStyles.all { it.start >= start && it.end <= end })
        assertTrue(projected.spanStyles.any { it.start == start && it.end == start + 5 && it.item.color == Color.Cyan })
        val clipped = projectCodeSyntax(input, colors, (start + 1) until (start + 4))
        assertEquals(input, clipped.text)
        assertEquals(start + 1, clipped.spanStyles.single().start)
        assertEquals(start + 4, clipped.spanStyles.single().end)
        val first = projectCodeSyntax(input, colors, 0 until input.indexOf('\n'))
        assertTrue(first.spanStyles.any { it.start == input.indexOf("var") && it.item.color == Color.Cyan })
        assertTrue(first.spanStyles.none { it.start >= start })
    }

    @Test
    fun canceledLargeProjectionStopsBeforeReturningAStaleDocument() = runTest {
        val job = Job()
        job.cancel()
        val result = runCatching {
            withContext(job) { projectCodeSyntax("return value;".repeat(100_000), colors) }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun everyRuleTokenKeepsFullProjectionColorsAtEachViewportBoundary() = runTest {
        val tokens = listOf(
            "||", "&&", "%%", "@js:", "@Json:", "@css:", "@@", "@XPath:", "@webjs:",
            "\"key\":", "\"", "{", "}", "[", "]", "\\n",
            ":", "==", ">", "<", "!=", ">=", "<=", "->", "=", "%", "-", "-=", "%=",
            "+", "+=", "^", "&", "|::", "?", "*",
            "var", "let", "const", "function", "return", "if", "else", "for", "while", "do",
            "break", "continue", "switch", "case", "default", "try", "catch", "finally",
            "throw", "new", "delete", "typeof", "instanceof", "in", "of", "void", "this",
            "true", "false", "null", "undefined",
        )
        val input = "😀 " + tokens.joinToString(" ") + " 中文"
        val full = projectCodeSyntax(input, colors)
        for (start in input.indices) {
            for (width in listOf(1, 2, 7, 17)) {
                assertViewportMatches(full, start until (start + width).coerceAtMost(input.length))
            }
        }
    }

    @Test
    fun clippedKeywordsRespectOriginalUnicodeAndIdentifierWordBoundaries() = runTest {
        val input = "😀 中文var var中文 _var var_ avar var9 \nvar 中文 return\ninstanceof"
        val full = projectCodeSyntax(input, colors)
        for (start in input.indices) {
            assertViewportMatches(full, start until (start + 1).coerceAtMost(input.length))
            assertViewportMatches(full, start until (start + 9).coerceAtMost(input.length))
        }
    }

    @Test
    fun viewportInsideLongJsonKeyMatchesFullTokenWithoutInventingMalformedKeys() = runTest {
        val key = "Abc123".repeat(3000)
        for (suffix in listOf("\": true", "\" true")) {
            val input = "😀 {\"$key$suffix}"
            val full = projectCodeSyntax(input, colors)
            val middle = input.length / 2
            assertViewportMatches(full, middle until middle + 1)
            assertViewportMatches(full, middle - 41 until middle + 83)
            val closingQuote = input.lastIndexOf('"')
            assertViewportMatches(full, closingQuote - 1 until closingQuote + 2)
        }
    }

    @Test
    fun emptyViewportsPreserveTheDocumentAndNeverPublishColorSpans() = runTest {
        for (input in listOf("", "😀 @js: var key = {\"key\": true};")) {
            for (position in listOf(0, input.length / 2, input.length).distinct()) {
                val projected = projectCodeSyntax(input, colors, position until position)
                assertEquals(input, projected.text)
                assertTrue("Empty viewport at $position must have no spans", projected.spanStyles.isEmpty())
            }
        }
    }

    private suspend fun assertViewportMatches(full: AnnotatedString, viewport: IntRange) {
        val projected = projectCodeSyntax(full.text, colors, viewport)
        assertEquals(full.text, projected.text)
        assertTrue(projected.spanStyles.all {
            it.start >= viewport.first && it.end <= viewport.last + 1 && it.start < it.end
        })
        // The full-document rendering is the oracle, including overlapping rule priorities.
        // Compare the effective ordered styles at every UTF-16 position, not region arithmetic.
        for (offset in viewport) {
            val expected = full.spanStyles.filter { offset >= it.start && offset < it.end }.map { it.item }
            val actual = projected.spanStyles.filter { offset >= it.start && offset < it.end }.map { it.item }
            assertEquals("Viewport=$viewport UTF-16 offset=$offset", expected, actual)
        }
    }

}
