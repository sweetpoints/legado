package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import java.io.StringWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BookshelfTransferJsonTest {
    @Test
    fun exportPreservesLegacyFieldsCustomIntroUnicodeAndReimportNames() = runTest {
        val writer = StringWriter()
        writeBookshelfExport(
            listOf(Book(name = "书名", author = "作者", intro = "old", customIntro = "custom")),
            writer,
        )
        val json = writer.toString()
        val row = GSON.fromJson(json, com.google.gson.JsonArray::class.java).single().asJsonObject
        assertEquals(setOf("name", "author", "intro"), row.keySet())
        assertEquals("custom", row["intro"].asString)
        assertEquals(listOf("书名" to "作者"), parseBookshelfImport(json))
    }

    @Test
    fun importAcceptsLegacyMissingAndNullAuthorsAndDeduplicatesPairs() {
        assertEquals(
            listOf("A" to "", "B" to "C"),
            parseBookshelfImport(
                """[{"name":"A"},{"name":"A","author":null},{"name":"B","author":"C","intro":"ignored"}]"""
            ),
        )
    }

    @Test
    fun invalidNamesAndNonStringAuthorsAreRejectedBeforeDatabaseWork() {
        listOf(
                """[{}]""",
                """[{"name":" "}]""",
                """[{"name":null}]""",
                """[{"name":2}]""",
                """[{"name":"A","author":{}}]""",
            )
            .forEach { json ->
                assertTrue(runCatching { parseBookshelfImport(json) }.isFailure)
            }
    }
}
