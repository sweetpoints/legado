package io.legado.app.ui.replace.edit

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.ReplaceRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Script replacements exercise the real production callbacks and native V8. */
@RunWith(AndroidJUnit4::class)
class ReplacePreviewV8Test {
    @Test
    // preview preserves js replacement semantics
    fun previewPreservesJsReplacementSemantics() {
        val rule =
            ReplaceRule(
                name = "uppercase",
                pattern = "[a-z]+",
                replacement = "@js:result.toUpperCase()",
                isRegex = true,
            )

        assertEquals("A 123 B", preview(rule, "a 123 b"))
    }

    @Test
    // preview allows book as a js string literal
    fun previewAllowsBookAsAJsStringLiteral() {
        val rule =
            ReplaceRule(
                pattern = ".",
                replacement = "@js:'book'",
                isRegex = true,
            )

        assertEquals("book", preview(rule, "x"))
    }

    @Test
    // preview stops an infinite js replacement at the rule timeout
    fun previewStopsAnInfiniteJsReplacementAtTheRuleTimeout() {
        val rule =
            ReplaceRule(
                pattern = ".",
                replacement = "@js:while (true) {}",
                isRegex = true,
                timeoutMillisecond = 25,
            )

        val error =
            assertThrows(ReplacePreviewException::class.java) {
                preview(rule, "x")
            }

        assertEquals(ReplacePreviewException.Reason.TIMEOUT, error.reason)
    }

    private fun preview(rule: ReplaceRule, sample: String): String = runBlocking {
        ReplacePreview.apply(rule, sample)
    }
}
