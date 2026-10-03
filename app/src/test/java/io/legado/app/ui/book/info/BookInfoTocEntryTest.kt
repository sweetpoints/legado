package io.legado.app.ui.book.info

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BookInfoTocEntryTest {

    @Test
    fun `missing book message is localized`() {
        val defaultStrings = readProjectFile("src/main/res/values/strings.xml")
        val chineseStrings = readProjectFile("src/main/res/values-zh/strings.xml")

        assertTrue(
            defaultStrings.contains("<string name=\"book_not_exist\">Book does not exist</string>")
        )
        assertTrue(chineseStrings.contains("<string name=\"book_not_exist\">书籍不存在</string>"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
