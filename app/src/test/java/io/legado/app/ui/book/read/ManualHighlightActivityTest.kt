package io.legado.app.ui.book.read

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualHighlightActivityTest {

    @Test
    fun `editing highlight is restored before fragments`() {
        val content =
            readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val onCreate = content.indexOf("override fun onCreate(savedInstanceState: Bundle?)")
        val restore = content.indexOf("editingHighlightTime =", onCreate)
        val superOnCreate = content.indexOf("super.onCreate(savedInstanceState)", onCreate)

        assertTrue(onCreate >= 0)
        assertTrue(restore in onCreate until superOnCreate)
        val ownerRestore =
            content.indexOf("editingHighlightOwner = savedInstanceState?.getString", onCreate)
        assertTrue(ownerRestore in onCreate until superOnCreate)
        assertTrue(content.contains("savedInstanceState?.getLong(STATE_EDITING_HIGHLIGHT)"))
        assertTrue(content.contains("outState.putLong(STATE_EDITING_HIGHLIGHT, it)"))
        assertTrue(content.contains("outState.putString(STATE_EDITING_HIGHLIGHT_OWNER, it)"))
        assertFalse(content.contains("putParcelable(STATE_EDITING_HIGHLIGHT"))
        assertTrue(
            content.contains(
                "ReadBook.book?.bookUrl?.let(MD5Utils::md5Encode) != editingHighlightOwner"
            )
        )
        assertTrue(content.contains("ReadBook.highlights.firstOrNull"))
        assertTrue(content.contains("findFragmentByTag(HighlightStyleDialog::class.simpleName)"))
    }

    @Test
    fun `legacy chapter rebinding only updates the owner url`() {
        val content = readProjectFile("src/main/java/io/legado/app/model/ReadBook.kt")

        assertTrue(content.contains("val legacyTimes = legacyBound.map { it.time }"))
        assertTrue(
            content.contains("bookHighlightDao.bindChapterUrl(legacyTimes, bookChapter.url)")
        )
        assertTrue(
            content.contains(
                ".sortedWith(compareBy(BookHighlight::chapterPos, BookHighlight::time))"
            )
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp")).first(File::isFile).readText()
    }
}
