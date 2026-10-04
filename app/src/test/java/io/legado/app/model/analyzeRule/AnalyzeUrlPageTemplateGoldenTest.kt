package io.legado.app.model.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fixed constructor goldens from the original JVM pipeline, with no HTTP requests. */
class AnalyzeUrlPageTemplateGoldenTest {
    private val body = AnalyzeUrl::class.java.getDeclaredField("body").apply {
        isAccessible = true
    }
    private val encodedForm = AnalyzeUrl::class.java.getDeclaredField("encodedForm").apply {
        isAccessible = true
    }

    private fun analyze(rule: String, page: Int) =
        AnalyzeUrl(rule, page = page, headerMapF = emptyMap())

    @Test
    fun pageArithmeticUsesOriginalJavaScriptBeforeUrlOptionsParsing() {
        for (page in 1..4) {
            val original = analyze(
                "https://fixture.invalid/search?next={{page+1}}&previous={{page - 1}}",
                page,
            )
            val expected = "https://fixture.invalid/search?next=${page + 1}&previous=${page - 1}"
            assertEquals(expected, original.ruleUrl)
            assertEquals(expected, original.url)
        }
    }

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
            val original = analyze(
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
    fun bodyPageArithmeticIsSubstitutedBeforeJsonOptionsAndFormEncoding() {
        val original = analyze(
            """https://fixture.invalid/search,{"method":"POST","body":"next={{page+1}}&previous={{page - 1}}&text=中文 +"}""",
            2,
        )
        assertEquals("next=3&previous=1&text=中文 +", body.get(original))
        assertEquals("next=3&previous=1&text=%E4%B8%AD%E6%96%87+%2B", encodedForm.get(original))
        assertEquals("https://fixture.invalid/search", original.url)
    }

    @Test
    fun pageChoiceAlsoMutatesXmlTagsInsideOptionsBody() {
        val original = analyze(
            """https://fixture.invalid/search,{"method":"POST","body":"<q>{{page+1}}</q>"}""",
            2,
        )
        // The old global <(.*?)> replacement includes both XML tags before JSON parsing.
        assertEquals("q3/q", body.get(original))
        assertEquals("q3%2Fq", encodedForm.get(original))
        assertEquals("https://fixture.invalid/search", original.url)
    }

    @Test
    fun xmlBodyWithoutPageInputKeepsTagsAndDoesNotBecomeForm() {
        val original = AnalyzeUrl(
            """https://fixture.invalid/search,{"method":"POST","body":"<q>value</q>"}""",
            headerMapF = emptyMap(),
        )
        assertEquals("<q>value</q>", body.get(original))
        assertNull(encodedForm.get(original))
    }
}
