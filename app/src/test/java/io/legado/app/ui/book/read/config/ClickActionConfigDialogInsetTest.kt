package io.legado.app.ui.book.read.config

import io.legado.app.data.preferences.ClickActionRegion
import org.junit.Assert.*
import org.junit.Test

/** The physical inset guarantee is exercised by ClickActionSettingsScreenTest on Android. */
class ClickActionConfigDialogInsetTest {
    @Test
    fun regionOrderingKeepsBottomRowIndependentOfNavigationPadding() {
        val rows = ClickActionRegion.entries.chunked(3)
        assertEquals(3, rows.size)
        assertEquals(
            listOf(
                ClickActionRegion.BottomLeft,
                ClickActionRegion.BottomCenter,
                ClickActionRegion.BottomRight,
            ),
            rows.last(),
        )
        assertEquals(9, rows.flatten().map { it.key }.toSet().size)
    }

    @Test
    fun allNineRegionKeysKeepExistingBackupsCompatible() {
        assertEquals(
            listOf(
                "clickActionTopLeft",
                "clickActionTopCenter",
                "clickActionTopRight",
                "clickActionMiddleLeft",
                "clickActionMiddleCenter",
                "clickActionMiddleRight",
                "clickActionBottomLeft",
                "clickActionBottomCenter",
                "clickActionBottomRight",
            ),
            ClickActionRegion.entries.map { it.key },
        )
    }
}
