package io.legado.app.model.analyzeRule

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed historical outputs, now executed through the actual V8 application pipeline. No HTTP. */
@RunWith(AndroidJUnit4::class)
class AnalyzeUrlV8TemplateGoldenTest {
    private val body = AnalyzeUrl::class.java.getDeclaredField("body").apply { isAccessible = true }
    private val encodedForm =
        AnalyzeUrl::class.java.getDeclaredField("encodedForm").apply { isAccessible = true }

    private fun analyze(rule: String, page: Int) =
        AnalyzeUrl(rule, page = page, headerMapF = emptyMap())

    private fun analyze(url: String, key: String) =
        AnalyzeUrl(url, key = key, headerMapF = emptyMap())

    @Test
    fun pageArithmeticUsesOriginalJavaScriptBeforeUrlOptionsParsing() {
        for (page in 1..4) {
            val original =
                analyze(
                    "https://fixture.invalid/search?next={{page+1}}&previous={{page - 1}}",
                    page,
                )
            val expected = "https://fixture.invalid/search?next=${page + 1}&previous=${page - 1}"
            assertEquals(expected, original.ruleUrl)
            assertEquals(expected, original.url)
        }
    }

    @Test
    fun bodyPageArithmeticIsSubstitutedBeforeJsonOptionsAndFormEncoding() {
        val original =
            analyze(
                """https://fixture.invalid/search,{"method":"POST","body":"next={{page+1}}&previous={{page - 1}}&text=中文 +"}""",
                2,
            )
        assertEquals("next=3&previous=1&text=中文 +", body.get(original))
        assertEquals("next=3&previous=1&text=%E4%B8%AD%E6%96%87+%2B", encodedForm.get(original))
        assertEquals("https://fixture.invalid/search", original.url)
    }

    @Test
    fun pageChoiceAlsoMutatesXmlTagsInsideOptionsBody() {
        val original =
            analyze(
                """https://fixture.invalid/search,{"method":"POST","body":"<q>{{page+1}}</q>"}""",
                2,
            )
        // The old global <(.*?)> replacement includes both XML tags before JSON parsing.
        assertEquals("q3/q", body.get(original))
        assertEquals("q3%2Fq", encodedForm.get(original))
        assertEquals("https://fixture.invalid/search", original.url)
    }

    @Test
    fun constructorReplacesTemplateThenParsesOptionsThenEncodesForm() {
        val original =
            analyze(
                """https://fixture.invalid/search,{"method":"POST","body":"&key={{key}}&literal=%20&&"}""",
                key = "中文 +",
            )
        assertEquals("key=%E4%B8%AD%E6%96%87+%2B&literal=%20&&", encodedForm.get(original))
    }

    @Test
    fun templateIntroducedFormSeparatorsAreParsedAfterSubstitution() {
        val original =
            analyze(
                """https://fixture.invalid/search,{"method":"POST","body":"key={{key}}&tail="}""",
                key = "a&next=b=c",
            )
        assertEquals("key=a&next=b%3Dc&tail=", encodedForm.get(original))
    }
}
