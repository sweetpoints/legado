package io.legado.app.ui.highlight

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class HighlightRuleUiContractTest {

    @Test
    fun `rule manager is registered and reachable from reading`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val readMenu = parseXml("src/main/res/menu/book_read.xml")
        val activity =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt").readText()

        assertTrue(manifest.contains(".ui.highlight.HighlightRuleActivity"))
        assertEquals(1, readMenu.elementsWithAndroidId("@+id/menu_highlight_rule"))
        assertTrue(
            activity.contains("R.id.menu_highlight_rule -> startActivity<HighlightRuleActivity>()")
        )
    }

    @Test
    fun `reading actions use the themed popup menu`() {
        val activity =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt").readText()

        assertTrue(activity.contains("ACTION_HIGHLIGHT_CREATE_RULE"))
        assertTrue(activity.contains("showContextMenu("))
        assertFalse(activity.contains("popupActionMenu(this)"))
        assertFalse(activity.contains("HighlightRulePopup(this"))
    }

    private fun parseXml(pathInApp: String): Element =
        DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = true
            }
            .newDocumentBuilder()
            .parse(projectFile(pathInApp))
            .documentElement

    private fun Element.elementsWithAndroidId(id: String): Int {
        val elements = getElementsByTagName("*")
        return (0 until elements.length)
            .map { elements.item(it) as Element }
            .count { it.getAttributeNS(ANDROID_NAMESPACE, "id") == id }
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
