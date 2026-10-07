package io.legado.app.ui.menu

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPopupStyleTest {

    @Test
    fun `popup background follows the custom theme surface`() {
        val materialValues =
            readProjectFile("src/main/java/io/legado/app/lib/theme/MaterialValueHelper.kt")

        assertContains(materialValues, "val Context.popupBackground: GradientDrawable")
        assertContains(materialValues, "background.cornerRadius = 12f.dpToPx()")
        assertContains(materialValues, "background.setColor(bottomBackground)")
    }

    @Test
    fun `custom popup windows apply the shared runtime background`() {
        val extension =
            readProjectFile("src/main/java/io/legado/app/utils/PopupWindowExtensions.kt")

        assertContains(extension, "fun PopupWindow.applyMd3PopupStyle()")
        assertContains(extension, "setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))")
        assertContains(extension, "elevation = 8f.dpToPx()")
        assertContains(extension, "contentView?.let { it.background = it.context.popupBackground }")
        assertFalse(extension.contains("contentView?.background == null"))

        listOf(
                "src/main/java/io/legado/app/ui/widget/PopupAction.kt",
                "src/main/java/io/legado/app/ui/book/audio/SliderPopup.kt",
            )
            .forEach { path ->
                assertContains(readProjectFile(path), "applyMd3PopupStyle()")
            }
    }

    @Test
    fun `legacy autocomplete widget has been retired`() {
        assertFalse(
            sequenceOf(
                    File("src/main/java/io/legado/app/ui/widget/text/AutoCompleteTextView.kt"),
                    File("app/src/main/java/io/legado/app/ui/widget/text/AutoCompleteTextView.kt"),
                )
                .any(File::isFile)
        )
    }

    @Test
    fun `native hosts inherit Material 3 popup and dialog styles`() {
        val document =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(readProjectFile("src/main/res/values/styles.xml").byteInputStream())
        val nodes = document.getElementsByTagName("style")
        val parents =
            (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as org.w3c.dom.Element
                element.getAttribute("name") to element.getAttribute("parent")
            }
        fun inheritsMaterial3(name: String, visited: Set<String> = emptySet()): Boolean {
            if (name in visited) return false
            val parent = parents[name].orEmpty().removePrefix("@style/")
            return parent.startsWith("Theme.Material3.") ||
                parent.startsWith("ThemeOverlay.Material3.") ||
                inheritsMaterial3(parent, visited + name)
        }
        listOf(
                "AppTheme.Light",
                "AppTheme.Dark",
                "Activity.Permission",
                "dialog_style",
                "ThemeOverlay.Legado.BottomWebViewDialog",
            )
            .forEach { name ->
                assertTrue(
                    "$name must inherit the complete Material3 style set",
                    inheritsMaterial3(name),
                )
            }
        assertFalse(
            parents.values.any {
                it.startsWith("Theme.Design.") ||
                    it.startsWith("Theme.AppCompat.") ||
                    it.startsWith("android:Theme.Holo")
            }
        )
    }

    private fun assertContains(text: String, expected: String) {
        assertTrue("Expected to contain $expected", text.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
