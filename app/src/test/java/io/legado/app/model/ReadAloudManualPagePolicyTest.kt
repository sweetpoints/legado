package io.legado.app.model

import io.legado.app.ui.book.read.config.navigateReadAloudChapter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudManualPagePolicyTest {

    @Test
    fun `enabled manual navigation restarts from visible page`() {
        assertTrue(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followManualPageTurns = true,
                followingReadAloudPosition = true,
            )
        )
    }

    @Test
    fun `disabled or speech driven navigation keeps existing behavior`() {
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followManualPageTurns = false,
                followingReadAloudPosition = true,
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = true,
                followManualPageTurns = true,
                followingReadAloudPosition = true,
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = false,
                speechDrivenNavigation = false,
                followManualPageTurns = true,
                followingReadAloudPosition = true,
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followManualPageTurns = true,
                followingReadAloudPosition = false,
            )
        )
    }

    @Test
    fun `dialog chapter controls follow the visible position after detaching`() {
        val calls = mutableListOf<String>()
        fun navigate(previous: Boolean, following: Boolean) {
            navigateReadAloudChapter(
                previous,
                following,
                { calls += "speechPrevious" },
                { calls += "speechNext" },
                { calls += "visiblePrevious" },
                { calls += "visibleNext" },
            )
        }
        navigate(previous = true, following = true)
        navigate(previous = false, following = true)
        navigate(previous = true, following = false)
        navigate(previous = false, following = false)
        assertEquals(
            listOf("speechPrevious", "speechNext", "visiblePrevious", "visibleNext"),
            calls,
        )
    }

    @Test
    fun `preference remains opt in and is wired to page navigation`() {
        val appConfig = readProjectFile("src/main/java/io/legado/app/help/config/AppConfig.kt")
        val readBook = readProjectFile("src/main/java/io/legado/app/model/ReadBook.kt")
        assertFalse(
            io.legado.app.data.preferences
                .ReadAloudPreferences(emptyMap())[
                    io.legado.app.data.preferences.ReadAloudSwitch.FollowManualPage]
        )
        assertTrue(appConfig.contains("PreferKey.readAloudFollowManualPage, false"))
        assertTrue(readBook.contains("prepareReadAloudPageNavigation"))
        assertTrue(
            readBook.lines().count {
                it.contains("restartReadAloudFromVisiblePage = restartReadAloud")
            } >= 3
        )
        assertTrue(readBook.contains("readAloud(!BaseReadAloudService.pause)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
