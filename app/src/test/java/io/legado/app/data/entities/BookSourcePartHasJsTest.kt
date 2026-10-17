package io.legado.app.data.entities

import io.legado.app.ui.book.source.manage.SourceManagerRow
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourcePartHasJsTest {

    private val partSource =
        projectFile("src/main/java/io/legado/app/data/entities/BookSourcePart.kt").readText()
    private val normalizedPartSource = partSource.replace(Regex("\\s+"), " ")
    private val databaseSource =
        projectFile("src/main/java/io/legado/app/data/AppDatabase.kt").readText()
    private val schemaSource =
        projectFile("schemas/io.legado.app.data.AppDatabase/93.json").readText()

    @Test
    fun `database view exposes js source state`() {
        assertTrue(partSource.contains("(mainJs is not null and trim(mainJs) <> '') hasJs"))
        assertTrue(
            normalizedPartSource.contains(
                "eventListener, bookSourceType, " +
                    "(mainJs is not null and trim(mainJs) <> '') hasJs"
            )
        )
        assertFalse(BookSourcePart().hasJs)
        assertTrue(BookSourcePart(hasJs = true).hasJs)
    }

    @Test
    fun `schema history preserves js source projection`() {
        val databaseVersion =
            Regex("""version = (\d+)""").find(databaseSource)?.groupValues?.get(1)?.toInt()
        assertTrue(requireNotNull(databaseVersion) >= 93)
        assertTrue(databaseSource.contains("AutoMigration(from = 92, to = 93)"))
        assertTrue(schemaSource.contains("\"version\": 93"))
        assertTrue(schemaSource.contains("(mainJs is not null and trim(mainJs) <> '') hasJs"))
    }

    @Test
    fun `native manager row preserves js projection for Flutter state`() {
        val ordinary = BookSourcePart(bookSourceUrl = "https://plain.example", hasJs = false)
        val scripted = BookSourcePart(bookSourceUrl = "https://script.example", hasJs = true)
        assertFalse(SourceManagerRow.from(ordinary).hasJs)
        assertTrue(SourceManagerRow.from(scripted).hasJs)
    }

    @Test
    fun `Flutter source row emits js badge conditionally without hidden spacing`() {
        val manager = sequenceOf(
            File("../flutter/apps/legado/lib/embedded/source_manager"),
            File("flutter/apps/legado/lib/embedded/source_manager"),
        ).first { it.isDirectory }
        val rows = manager.walkTopDown().filter { it.extension == "dart" }
            .map(File::readText).joinToString("\n")
        assertTrue("Flutter must conditionally insert the JS badge using the projected flag",
            Regex("""if\s*\(row\.hasJs\)\s*""").containsMatchIn(rows))
        assertTrue("JS flag must render the localized JS source badge",
            Regex("""\b\w+\.sourceManagerJsBadge\b""").containsMatchIn(rows))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
    }
}
