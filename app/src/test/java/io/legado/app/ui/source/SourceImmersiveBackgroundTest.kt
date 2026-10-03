package io.legado.app.ui.source

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SourceImmersiveBackgroundTest {

    @Test
    fun legacyTitleBarRetainsInkAndTransparencyRules() {
        val titleBar = projectFile("src/main/java/io/legado/app/ui/widget/TitleBar.kt").readText()
        assertTrue(titleBar.contains("if (AppConfig.isEInkMode)"))
        assertTrue(titleBar.contains("else if (!opaque && context.transparentNavBar)"))
    }

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
                ".background(if (transparentNavigation) Color.Transparent else colors.bottomBackground)"
            )
        )
    }

    @Test
    fun transparentTitleBarsUseVisibleBackgroundForContrast() {
        val theme =
            projectFile("src/main/java/io/legado/app/lib/theme/MaterialValueHelper.kt").readText()
        assertTrue(
            theme.contains("toolbarBackgroundColor(transparentBar, primaryColor, backgroundColor)")
        )

        val titleBar = projectFile("src/main/java/io/legado/app/ui/widget/TitleBar.kt").readText()
        assertTrue(titleBar.contains("automaticForeground = themeMode == 0 && !opaque"))
        assertTrue(titleBar.contains("!AppConfig.isEInkMode && background?.alpha == 0"))
        assertTrue(titleBar.contains("context.getToolbarTextColor(true)"))
        assertTrue(titleBar.contains("toolbar.navigationIcon?.colorFilter = colorFilter"))
        assertTrue(titleBar.contains("toolbar.overflowIcon?.colorFilter = colorFilter"))
        assertTrue(
            titleBar.contains("findViewById<SearchView>(R.id.search_view)?.applyTint(color)")
        )
        assertTrue(titleBar.contains("findViewById<TabLayout>(R.id.tab_layout)"))
        assertTrue(titleBar.contains("setTabTextColors(tabUnselectedColor, color)"))
        assertTrue(titleBar.contains("R.color.md_light_secondary"))
        assertTrue(titleBar.contains("R.color.md_dark_secondary"))
        assertTrue(titleBar.contains("toolbar.menu.forEach { item ->"))

        val menu = projectFile("src/main/java/io/legado/app/utils/MenuExtensions.kt").readText()
        assertTrue(menu.contains("(impl.actionView as? SearchView)?.applyTint(tintColor)"))

        val activity =
            projectFile("src/main/java/io/legado/app/base/BaseThemedActivity.kt").readText()
        val fragment = projectFile("src/main/java/io/legado/app/base/BaseFragment.kt").readText()
        assertTrue(
            activity.contains("transparentBar = titleBar?.usesTransparentForeground == true")
        )
        assertTrue(fragment.contains("titleBar?.post { titleBar.applyForegroundColor() }"))
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
