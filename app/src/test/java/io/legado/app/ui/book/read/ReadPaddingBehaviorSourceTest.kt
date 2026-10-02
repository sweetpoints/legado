package io.legado.app.ui.book.read

import io.legado.app.ui.book.read.config.PaddingPanelVisibility
import io.legado.app.data.preferences.PaddingRegion
import io.legado.app.data.preferences.paddingRegionEvents
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadPaddingBehaviorSourceTest {

    @Test
    fun `detail seek bar preserves final callback order`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/DetailSeekBar.kt"
        ).readText().normalizeLines()

        assertTrue(source.contains("if (fromUser) onDragging?.invoke(progress)"))
        assertTrue(source.contains("onTrackingStart?.invoke()"))
        val stopBlock = source.substringAfter("override fun onStopTrackingTouch")
            .substringBefore("\n    }")
        assertTrue(stopBlock.indexOf("onChanged?.invoke") < stopBlock.indexOf("onTrackingStop?.invoke"))
    }

    @Test
    fun `detail seek bar owns its saved progress state`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/DetailSeekBar.kt"
        ).readText().normalizeLines()

        assertTrue(source.contains("dispatchFreezeSelfOnly(container)"))
        assertTrue(source.contains("dispatchThawSelfOnly(container)"))
        assertTrue(source.contains("putInt(STATE_PROGRESS, progress)"))
        assertTrue(source.contains("progress = state.getInt(STATE_PROGRESS)"))
    }

    @Test fun visibilityLeaseBalancesDuplicateDismissAndViewRecreation() {
        val host = object : PaddingPanelVisibility.Owner { override var bottomDialog = 1 }
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

    @Test fun visibilityLeaseReleasesTheOldActivityWhenConfigurationChanges() {
        val oldHost = object : PaddingPanelVisibility.Owner { override var bottomDialog = 0 }
        val newHost = object : PaddingPanelVisibility.Owner { override var bottomDialog = 0 }
        val panel = PaddingPanelVisibility()
        panel.acquire(oldHost)
        panel.acquire(newHost)
        org.junit.Assert.assertEquals(0, oldHost.bottomDialog)
        org.junit.Assert.assertEquals(1, newHost.bottomDialog)
        panel.release()
        org.junit.Assert.assertEquals(0, newHost.bottomDialog)
    }

    @Test fun bodyAndInformationPaddingKeepTheirExistingUpdatePayloads() {
        org.junit.Assert.assertEquals(arrayListOf(10, 5), paddingRegionEvents(PaddingRegion.BODY))
        org.junit.Assert.assertEquals(arrayListOf(2), paddingRegionEvents(PaddingRegion.HEADER))
        org.junit.Assert.assertEquals(arrayListOf(2), paddingRegionEvents(PaddingRegion.FOOTER))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
