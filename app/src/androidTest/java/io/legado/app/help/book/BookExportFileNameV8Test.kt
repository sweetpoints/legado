package io.legado.app.help.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production export entry points against the Android V8 backend. */
@RunWith(AndroidJUnit4::class)
class BookExportFileNameV8Test {
    private var originalExportRule: String? = null
    private val book = Book(name = "书/名", author = "作|者")

    @Before
    fun saveExportRule() {
        originalExportRule = AppConfig.bookExportFileName
    }

    @After
    fun restoreExportRule() {
        AppConfig.bookExportFileName = originalExportRule
    }

    @Test
    fun ordinaryExportNormalizesDefaultAndCustomResults() {
        AppConfig.bookExportFileName = null
        assertEquals("书_名 作者：作_者.txt", book.getExportFileName("txt"))
        AppConfig.bookExportFileName = "name + ':' + author + ':' + epubIndex"
        assertEquals("书_名_作_者_.txt", book.getExportFileName("txt"))
    }

    @Test
    fun splitExportNormalizesDefaultAndCustomResults() {
        assertEquals("书_名 作者：作_者 [2].epub", book.getExportFileName("epub", 2, null))
        assertEquals(
            "书_名_作_者_2.epub",
            book.getExportFileName("epub", 2, "name + ':' + author + ':' + epubIndex"),
        )
    }

    @Test
    fun emptyAndFailedScriptsFallBackInBothOverloads() {
        listOf("null", "undefined", "''", "'   '", "throw new Error('export failed')")
            .forEach { script ->
                AppConfig.bookExportFileName = script
                assertEquals(script, "书_名 作者：作_者.txt", book.getExportFileName("txt"))
                assertEquals(script, "书_名 作者：作_者 [2].epub", book.getExportFileName("epub", 2, script))
            }
    }

    @Test
    fun ruleValidationUsesActualJavaScriptResults() {
        listOf("null", "undefined", "''", "'   '", "throw new Error('invalid')")
            .forEach { assertFalse(it, tryParesExportFileName(it)) }
        listOf("name + ' ' + author", "0", "false")
            .forEach { assertTrue(it, tryParesExportFileName(it)) }
    }

    @Test
    fun scalarScriptResultsKeepTheirJavaScriptValues() {
        assertEquals("0.epub", book.getExportFileName("epub", 2, "0"))
        assertEquals("false.epub", book.getExportFileName("epub", 2, "false"))
    }
}
