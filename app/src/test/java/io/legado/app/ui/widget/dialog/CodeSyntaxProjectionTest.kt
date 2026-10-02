package io.legado.app.ui.widget.dialog

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CodeSyntaxProjectionTest {
    private val colors = CodeSyntaxColors(Color.Red, Color.Blue, Color.Gray, Color.Green, Color.Cyan)
    @Test fun actualPatternsRetainAllTextAndUtf16OffsetsForEmojiAndMultiline() = runTest {
        val input = "😀 @js: const value = {\"key\": true};\nreturn value;"
        val projected = projectCodeSyntax(input, colors)
        assertEquals(input, projected.text)
        val keyword = input.indexOf("const")
        assertTrue(projected.spanStyles.any { it.start == keyword && it.end == keyword + 5 && it.item.color == Color.Cyan })
        val rule = input.indexOf("@js:")
        assertTrue(projected.spanStyles.any { it.start == rule && it.end == rule + 4 && it.item.color == Color.Red })
        assertTrue(projected.spanStyles.all { it.start in 0..input.length && it.end in 0..input.length })
    }
    @Test fun plainTextAndEmptyDocumentsHaveNoInventedSyntaxOrChangedOffsets() = runTest {
        listOf("", "plain 中文 😀").forEach { input ->
            val projected = projectCodeSyntax(input, colors); assertEquals(input, projected.text); assertTrue(projected.spanStyles.isEmpty())
        }
    }
    @Test fun canceledLargeProjectionStopsBeforeReturningAStaleDocument() = runTest {
        val job = Job(); job.cancel()
        val result = runCatching { withContext(job) { projectCodeSyntax("return value;".repeat(100_000), colors) } }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
