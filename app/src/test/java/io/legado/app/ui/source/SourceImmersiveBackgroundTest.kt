package io.legado.app.ui.source

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SourceImmersiveBackgroundTest {

    @Test
    fun transparentBottomBarsUseVisibleBackgroundForContrast() {
        mapOf(
                "src/main/java/io/legado/app/ui/widget/text/AccentStrokeTextView.kt" to
                    "if (context.transparentNavBar) context.backgroundColor else context.bottomBackground"
            )
            .forEach { (path, expression) ->
                assertTrue(projectFile(path).readText().contains(expression))
            }

        val mainScreen = projectFile("src/main/java/io/legado/app/ui/main/MainScreen.kt").readText()
        assertTrue(
            mainScreen.contains(
                "if (transparentNavigation) Color.Transparent else colors.bottomBackground"
            )
        )
    }

    @Test
    fun navigationBarsDisablePlatformContrastScrimOnAndroidQ() {
        val source =
            projectFile("src/main/java/io/legado/app/utils/ActivityExtensions.kt")
                .readText()
                .substringAfter("fun Activity.setNavigationBarColorAuto")
                .substringBefore("\nfun ")
        assertTrue(source.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)"))
        assertTrue(source.contains("window.isNavigationBarContrastEnforced = false"))
    }

    private fun viewById(layout: String, id: String): Element {
        val document =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(projectFile("src/main/res/layout/$layout"))
        return document.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttribute("android:id") == "@+id/$id" }
        }
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
