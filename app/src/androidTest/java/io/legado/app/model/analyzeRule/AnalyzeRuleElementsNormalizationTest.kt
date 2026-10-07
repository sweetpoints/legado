package io.legado.app.model.analyzeRule

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalyzeRuleElementsNormalizationTest {

    @Test
    fun javascriptArrayResultsBecomeUsableElementLists() {
        val analyzeRule = AnalyzeRule().setContent("ignored")

        assertEquals(
            listOf(1.0, 2.0),
            analyzeRule.getElements("@js:[1, null, 2]")?.map { (it as Number).toDouble() },
        )
    }
}
