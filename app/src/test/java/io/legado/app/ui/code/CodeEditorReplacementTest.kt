package io.legado.app.ui.code

import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodeEditorReplacementTest {
    @Test
    fun regexBackReferencesKeepUtf16CrLfAndOptionalUnmatchedGroups() = runBlocking {
        val source = "😀\r\nalpha alpha!"
        val request =
            CodeEditorReplacement(
                source,
                "(alpha)(!)?",
                true,
                false,
                RegexBackrefGrammar.DEFAULT,
                false,
                listOf(CodeEditorMatch(4, 9), CodeEditorMatch(10, 16)),
            )
        assertEquals("😀\r\n[alpha:] [alpha:!]", replaceCodeEditorMatches(request, "[$1:$2]"))
    }

    @Test
    fun literalReplacementKeepsPreserveCaseAndLengthOffsets() = runBlocking {
        val request =
            CodeEditorReplacement(
                "ALPHA alpha Alpha",
                "alpha",
                false,
                true,
                null,
                true,
                listOf(CodeEditorMatch(0, 5), CodeEditorMatch(6, 11), CodeEditorMatch(12, 17)),
            )
        assertEquals("BETA beta Beta", replaceCodeEditorMatches(request, "beta"))
    }

    @Test
    fun contextDependentRegexKeepsOriginalSoraIsolatedRegionBehavior() = runBlocking {
        val request =
            CodeEditorReplacement(
                "xalpha",
                "(?<=x)alpha",
                true,
                false,
                RegexBackrefGrammar.DEFAULT,
                false,
                listOf(CodeEditorMatch(1, 6)),
            )
        assertEquals("xalpha", replaceCodeEditorMatches(request, "beta"))
    }

    @Test
    fun cancelledComputationCannotProduceReplacement() = runBlocking {
        var completed = false
        val computation = launch {
            cancel()
            try {
                val request =
                    CodeEditorReplacement(
                        "alpha",
                        "alpha",
                        false,
                        false,
                        null,
                        false,
                        listOf(CodeEditorMatch(0, 5)),
                    )
                replaceCodeEditorMatches(request, "beta")
                completed = true
            } catch (_: CancellationException) {
                // A cancelled worker must not return text that could be accepted on Main.
            }
        }
        computation.join()
        assertFalse(completed)
    }
}
