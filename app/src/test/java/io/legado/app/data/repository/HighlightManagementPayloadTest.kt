package io.legado.app.data.repository

import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test

class HighlightManagementPayloadTest {
    @Test
    fun selectionExportsAllFieldsInTheImportEnvelopeWithoutUnselectedRules() {
        val row =
            HighlightRule(
                id = 42,
                name = "Name",
                pattern = "甲乙[?&]",
                isRegex = true,
                scope = "Book",
                isEnabled = false,
                style = "{\"textColor\":123456}",
                order = -8,
                timeoutMillisecond = 4567,
                group = "A,B",
                applyToTitle = true,
                applyToBody = false,
            )
        val restored =
            GSON.fromJson(
                highlightManagementJson(listOf(HighlightManagedRule.from(row))),
                HighlightRuleFile::class.java,
            )
        assertEquals(HighlightRuleFile.TYPE, restored.type)
        assertEquals(GSON.toJson(row), GSON.toJson(restored.rules!!.single()))
    }

    @Test
    fun snapshotDoesNotShareMutableEntityAndGroupIsOneLiteralLabel() {
        val row = HighlightRule(name = "", pattern = "Pattern", group = "A,B", style = "original")
        val snapshot = HighlightManagedRule.from(row)
        row.name = "changed"
        row.style = "changed"
        row.group = null
        assertEquals("[A,B] Pattern", snapshot.label)
        assertEquals("original", snapshot.style)
        assertEquals("Pattern", snapshot.copy(group = " ").label)
        assertEquals("Pattern", snapshot.copy(group = null).displayName)
    }
}
