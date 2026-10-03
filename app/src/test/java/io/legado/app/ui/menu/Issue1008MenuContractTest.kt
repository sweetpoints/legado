package io.legado.app.ui.menu

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Issue1008MenuContractTest {

    @Test
    fun sourceIconsAndRssOverflowFollowReportedMenuContract() {
        val source =
            readProjectFile(
                "src/main/java/io/legado/app/ui/book/source/manage/BookSourceManagerScreen.kt"
            )
        assertTrue(source.contains("R.drawable.ic_add_online"))
        assertTrue(source.contains("action == \"domain\""))

        // RSS overflow behavior is covered by RssReaderScreenTest using the real Compose menu.

    }

    private fun assertItemIcon(path: String, id: String, drawable: String) {
        val item = itemBlock(readProjectFile(path), id)
        assertContains(path, item, """android:icon="@drawable/$drawable"""")
    }

    private fun itemBlock(source: String, id: String): String {
        val marker = """android:id="@+id/$id""""
        val start = source.indexOf(marker)
        assertTrue("$id should exist", start >= 0)
        val end = source.indexOf("/>", start)
        assertTrue("$id should have a complete item", end > start)
        return source.substring(start, end)
    }

    private fun assertContains(path: String, source: String, expected: String) {
        assertTrue("$path should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
