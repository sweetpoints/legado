package io.legado.app.ui.login

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginEntryRoutingTest {

    @Test
    fun `login entry points use unified login capability`() {
        val paths =
            listOf(
                "src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt",
                "src/main/java/io/legado/app/ui/book/read/ReaderMenuController.kt",
                "src/main/java/io/legado/app/ui/video/VideoPlayerActivity.kt",
                "src/main/java/io/legado/app/ui/rss/read/RssJsExtensions.kt",
            )

        paths.forEach { path ->
            assertTrue("$path should use hasLogin()", File(path).readText().contains("hasLogin()"))
        }
    }
}
