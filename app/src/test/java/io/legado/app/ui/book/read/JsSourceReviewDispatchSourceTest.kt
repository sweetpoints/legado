package io.legado.app.ui.book.read

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceReviewDispatchSourceTest {

    @Test
    fun `JavaScript review dispatch runs before declarative rule gates`() {
        val activity =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
                .readText()
                .normalizeLines()
        val clickBlock =
            activity
                .substringAfter("override fun onReviewClick(")
                .substringBefore("private fun loadReviewSummaryIfNeeded(")
        val summaryBlock =
            activity
                .substringAfter("private fun loadReviewSummaryIfNeeded(")
                .substringBefore("private fun loadJsReviewSummaryIfNeeded(")
        val jsSummaryBlock =
            activity
                .substringAfter("private fun loadJsReviewSummaryIfNeeded(")
                .substringBefore("private fun clearReviewSummaryProviders(")

        assertTrue(
            clickBlock.indexOf("if (source.isJsSource())") < clickBlock.indexOf("source.ruleReview")
        )
        assertTrue(!clickBlock.contains("ReadBook.durChapterIndex"))
        assertEquals(
            2,
            Regex("""chapterIndex\s*=\s*chapterIndex""").findAll(clickBlock).count(),
        )
        assertEquals(
            2,
            Regex(
                    """paragraphData\s*=\s*ChapterProvider\.getReviewKeyById\(""" +
                        """paragraphNum,\s*chapterIndex\)"""
                )
                .findAll(clickBlock)
                .count(),
        )
        assertTrue(
            summaryBlock.indexOf("if (source.isJsSource())") <
                summaryBlock.indexOf("source.ruleReview")
        )
        assertTrue(clickBlock.contains("ruleHash = source.mainJs.hashCode()"))
        assertTrue(jsSummaryBlock.contains("val sourceHash = source.mainJs.hashCode()"))
        assertTrue(
            jsSummaryBlock.contains("buildReviewSummaryKey(book, source, sourceHash, chapterIndex)")
        )
        assertTrue(jsSummaryBlock.contains("reviewSummaryAppliedKey = key"))
    }

    @Test
    fun `review summary refresh preserves read aloud position on the main thread`() {
        val activity =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
                .readText()
                .normalizeLines()
        val applyBlock =
            activity
                .substringAfter("private fun applyReviewSummary(")
                .substringBefore("private fun prefetchAdjacentReviewSummary(")
        val contentLoadFinishBlock =
            activity
                .substringAfter("override fun contentLoadFinish()")
                .substringBefore("override fun upContent(")

        assertTrue(
            applyBlock.contains("readView.upContent(relativePosition = 0, resetPageOffset = false)")
        )
        assertTrue(
            applyBlock.indexOf("ChapterProvider.setReviewProviders(") <
                applyBlock.indexOf("readView.upContent(")
        )
        assertTrue(!applyBlock.contains("ReadBook.loadContent("))
        assertTrue(applyBlock.contains("chapterIndex = chapterIndex"))
        assertTrue(contentLoadFinishBlock.contains("lifecycleScope.launch(Main.immediate)"))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
