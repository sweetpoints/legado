package io.legado.app.ui.welcome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WelcomeActivityLifecycleContractTest {

    private val source by lazy {
        projectFile(
            "src/main/java/io/legado/app/ui/welcome/WelcomeActivity.kt"
        ).readText().replace("\r\n", "\n")
    }

    @Test
    fun `delayed main launch belongs to activity lifecycle`() {
        val onActivityCreated = section(
            "override fun onComposeCreated",
            "override fun setupSystemBar",
        )

        assertTrue(source.contains("private var startMainJob: Job? = null"))
        assertTrue(onActivityCreated.contains("startMainJob = lifecycleScope.launch"))
        assertTrue(onActivityCreated.contains("delay(welcomeShowTime.toLong())"))
        val delayIndex = onActivityCreated.indexOf("delay(welcomeShowTime.toLong())")
        assertTrue(onActivityCreated.indexOf("startMainActivity()", delayIndex) > delayIndex)
        assertFalse(source.contains("postDelayed"))
    }

    @Test
    fun `finishing welcome cancels delayed main launch first`() {
        val finish = section("override fun finish()", "override fun upBackgroundImage")

        assertTrue(finish.contains("startMainJob?.cancel()"))
        assertTrue(finish.indexOf("startMainJob?.cancel()") < finish.indexOf("super.finish()"))
    }

    @Test
    fun `welcome content visibility does not depend on custom background`() {
        val state = section("private val welcomeUiState", "private var startMainJob")
        assertTrue(state.contains("showText = if (dark) AppConfig.welcomeShowTextDark else AppConfig.welcomeShowText"))
        assertTrue(state.contains("showIcon = if (dark) AppConfig.welcomeShowIconDark else AppConfig.welcomeShowIcon"))
        assertTrue(state.contains("WelcomeScreen(welcomeUiState)"))
        assertFalse(state.contains("PreferKey.customWelcome"))
        val screen = projectFile("src/main/java/io/legado/app/ui/welcome/WelcomeScreen.kt").readText()
        assertTrue(screen.contains("if (state.showText)"))
        assertTrue(screen.contains("if (state.showIcon)"))
        val background = section("override fun upBackgroundImage()", "private fun startMainActivity")
        assertFalse(background.contains("welcomeUiState"))
        assertTrue(background.contains("withContext(Dispatchers.IO)"))
    }

    private fun section(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start)
        require(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
