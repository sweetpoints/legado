package io.legado.app.ui.book.read

import io.legado.app.data.preferences.PaddingRegion
import io.legado.app.data.preferences.paddingRegionEvents
import io.legado.app.ui.book.read.config.PaddingPanelVisibility
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class ReadPaddingBehaviorSourceTest {

    @Test
    fun `legacy detail seek bar implementation and layout are retired`() {
        assertFalse(projectFile("src/main/java/io/legado/app/ui/widget/DetailSeekBar.kt").exists())
        assertFalse(projectFile("src/main/res/layout/view_detail_seek_bar.xml").exists())
    }

    @Test
    fun visibilityLeaseBalancesDuplicateDismissAndViewRecreation() {
        val host =
            object : PaddingPanelVisibility.Owner {
                override var bottomDialog = 1
            }
        val panel = PaddingPanelVisibility()
        panel.acquire(host)
        panel.acquire(host)
        org.junit.Assert.assertEquals(2, host.bottomDialog)
        panel.release()
        panel.release()
        org.junit.Assert.assertEquals(1, host.bottomDialog)
        panel.acquire(host)
        org.junit.Assert.assertEquals(2, host.bottomDialog)
        panel.release()
        org.junit.Assert.assertEquals(1, host.bottomDialog)
    }

    @Test
    fun visibilityLeaseReleasesTheOldActivityWhenConfigurationChanges() {
        val oldHost =
            object : PaddingPanelVisibility.Owner {
                override var bottomDialog = 0
            }
        val newHost =
            object : PaddingPanelVisibility.Owner {
                override var bottomDialog = 0
            }
        val panel = PaddingPanelVisibility()
        panel.acquire(oldHost)
        panel.acquire(newHost)
        org.junit.Assert.assertEquals(0, oldHost.bottomDialog)
        org.junit.Assert.assertEquals(1, newHost.bottomDialog)
        panel.release()
        org.junit.Assert.assertEquals(0, newHost.bottomDialog)
    }

    @Test
    fun bodyAndInformationPaddingKeepTheirExistingUpdatePayloads() {
        org.junit.Assert.assertEquals(arrayListOf(10, 5), paddingRegionEvents(PaddingRegion.BODY))
        org.junit.Assert.assertEquals(arrayListOf(2), paddingRegionEvents(PaddingRegion.HEADER))
        org.junit.Assert.assertEquals(arrayListOf(2), paddingRegionEvents(PaddingRegion.FOOTER))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
