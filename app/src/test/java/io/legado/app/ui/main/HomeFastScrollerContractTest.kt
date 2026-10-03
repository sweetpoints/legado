package io.legado.app.ui.main

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFastScrollerContractTest {
    @Test
    fun `discovery keeps independent Compose scroller and existing setting`() {
        val screen =
            projectFile("src/main/java/io/legado/app/ui/main/explore/ExploreHomeScreen.kt")
                .readText()
        val repository =
            projectFile("src/main/java/io/legado/app/ui/main/explore/ExploreHomeRepository.kt")
                .readText()
        assertTrue(screen.contains("state.showFastScroller"))
        assertTrue(screen.contains("explore-home-scroller"))
        assertTrue(screen.contains("detectDragGestures"))
        assertTrue(repository.contains("AppConfig.showDiscoveryFastScroller"))
        val setting = io.legado.app.model.settings.OtherSwitch.DiscoveryScroller
        assertEquals("showDiscoveryFastScroller", setting.key)
        assertEquals(
            false,
            io.legado.app.model.settings.OtherSettingsSnapshot().switches.getValue(setting),
        )
    }

    private fun projectFile(path: String): File =
        sequenceOf(File(path), File("app/$path")).first(File::isFile)
}
