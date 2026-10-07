package io.legado.app.model.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fixed constructor goldens from the original JVM pipeline, with no HTTP requests. */
class AnalyzeUrlPageTemplateGoldenTest {
    private val body =
        AnalyzeUrl::class.java.getDeclaredField("body").apply {
            isAccessible = true
        }
    private val encodedForm =
        AnalyzeUrl::class.java.getDeclaredField("encodedForm").apply {
            isAccessible = true
        }

    private fun analyze(rule: String, page: Int) =
        AnalyzeUrl(rule, page = page, headerMapF = emptyMap())

    @Test
    fun pageChoicesSelectFirstSecondLastAndThenRepeatLast() {
        val choices = listOf("first", "second", "last", "last")
        for ((index, choice) in choices.withIndex()) {
            val original = analyze("https://fixture.invalid/<first,second,last>", index + 1)
            assertEquals("https://fixture.invalid/$choice", original.ruleUrl)
            assertEquals("https://fixture.invalid/$choice", original.url)
        }
    }

    @Test
    fun selectedPageFragmentTrimsJavaSpaceRange() {
        // Tab and U+001F both satisfy the original Kotlin trim { it <= ' ' }.
        val choices = listOf("first", "second", "last", "last")
        for ((index, choice) in choices.withIndex()) {
            val original =
                analyze(
                    "https://fixture.invalid/<\t first \u001f, second\t , \u001flast >",
                    index + 1,
                )
            assertEquals("https://fixture.invalid/$choice", original.ruleUrl)
        }
    }

    @Test
    fun nonPositivePageChoiceIsOutsideOriginalAcceptedDomain() {
        // This records the old boundary; a new engine should reject such pages explicitly.
        for (page in listOf(0, -1)) {
            assertThrows(IndexOutOfBoundsException::class.java) {
                analyze("https://fixture.invalid/<first,second,last>", page)
            }
        }
    }

    @Test
    fun xmlBodyWithoutPageInputKeepsTagsAndDoesNotBecomeForm() {
        val original =
            AnalyzeUrl(
                """https://fixture.invalid/search,{"method":"POST","body":"<q>value</q>"}""",
                headerMapF = emptyMap(),
            )
        assertEquals("<q>value</q>", body.get(original))
        assertNull(encodedForm.get(original))
    }
}
