package io.legado.app.model.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Test

/** Calls the original JVM implementation; expected values are fixed contract goldens. */
class AnalyzeUrlFormEncodingGoldenTest {
    private val encodeParams = AnalyzeUrl::class.java.getDeclaredMethod(
        "encodeParams", String::class.java, String::class.java, Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }

    private val encodedForm = AnalyzeUrl::class.java.getDeclaredField("encodedForm").apply {
        isAccessible = true
    }

    private fun analyze(url: String = "https://fixture.invalid/search", key: String? = null) =
        AnalyzeUrl(url, key = key, headerMapF = emptyMap())

    @Test
    fun originalNoCharsetFormEncodingGoldens() {
        val original = analyze()
        val cases = listOf(
            "" to "",
            "&&" to "",
            "&a=1" to "a=1",
            "&&a=1" to "a=1",
            "a=1&" to "a=1&",
            "a=1&&b=2&&" to "a=1&&b=2&&",
            "=value" to "=value",
            "=&a=" to "=&a=",
            "bare" to "bare",
            "中文=中文 空格" to "%E4%B8%AD%E6%96%87=%E4%B8%AD%E6%96%87+%E7%A9%BA%E6%A0%BC",
            "x=a+b" to "x=a%2Bb",
            "x=%20&y=%e4%b8%ad" to "x=%20&y=%e4%b8%ad",
            "x=%20 +" to "x=%2520+%2B",
            "x=%GG&y=%&z=%A" to "x=%25GG&y=%25&z=%25A",
            "x=a=b&k=v" to "x=a%3Db&k=v",
            "a=1&a=2" to "a=1&a=2",
        )
        for ((input, expected) in cases) {
            assertEquals("form input: $input", expected, encodeParams.invoke(original, input, null, false))
        }
    }

    @Test
    fun constructorReplacesTemplateThenParsesOptionsThenEncodesForm() {
        val original = analyze(
            """https://fixture.invalid/search,{"method":"POST","body":"&key={{key}}&literal=%20&&"}""",
            key = "中文 +",
        )
        assertEquals("key=%E4%B8%AD%E6%96%87+%2B&literal=%20&&", encodedForm.get(original))
    }

    @Test
    fun templateIntroducedFormSeparatorsAreParsedAfterSubstitution() {
        val original = analyze(
            """https://fixture.invalid/search,{"method":"POST","body":"key={{key}}&tail="}""",
            key = "a&next=b=c",
        )
        assertEquals("key=a&next=b%3Dc&tail=", encodedForm.get(original))
    }

    @Test
    fun constructorKeepsEmptyFormBody() {
        val original = analyze(
            """https://fixture.invalid/search,{"method":"POST","body":""}""",
        )
        assertEquals("", encodedForm.get(original))
    }
}
