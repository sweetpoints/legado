package io.legado.app.data.repository

import io.legado.app.data.entities.RssSource
import org.junit.Assert.*
import org.junit.Test

class RssSourceManagementRowTest {
    @Test
    fun projectionKeepsOriginalDisplayFormattingWithoutRetainingMutableEntity() {
        val source =
            RssSource(
                sourceUrl = "https://owned.invalid",
                sourceName = "Owned",
                sourceGroup = "A,B",
                customOrder = 7,
                enabled = false,
            )
        val row = rssSourceManagementRow(source)
        source.sourceName = "Changed"
        source.sourceGroup = "Changed"
        source.enabled = true
        assertEquals("Owned (A,B)", row.displayName)
        assertEquals("Owned", row.name)
        assertEquals("A,B", row.group)
        assertFalse(row.enabled)
        assertEquals(7, row.order)
    }

    @Test
    fun stableIdsAreSmallAndDifferentForExactUnicodeUrls() {
        val url = "https://" + "字".repeat(100000)
        assertEquals(64, rssSourceManagementId(url).length)
        assertEquals(rssSourceManagementId(url), rssSourceManagementId(url))
        assertNotEquals(rssSourceManagementId(url), rssSourceManagementId(url + "/"))
    }
}
