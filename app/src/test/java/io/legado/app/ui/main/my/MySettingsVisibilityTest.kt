package io.legado.app.ui.main.my

import org.junit.Assert.*
import org.junit.Test

class MySettingsVisibilityTest {
    @Test fun defaultUpdateActionsLiveOnlyInMore() {
        val main = visibleMySettings(defaultMyMoreItems, false).map { it.key }
        val more = visibleMySettings(defaultMyMoreItems, true).map { it.key }
        assertEquals(defaultMyMoreItems, more.toSet())
        assertFalse(main.any { it in defaultMyMoreItems })
        assertTrue("exit" in main && "myMore" in main)
    }

    @Test fun mainAndMorePartitionCustomizableItemsForEverySelection() {
        val choices = listOf(emptySet(), defaultMyMoreItems, setOf("webService", "mcpService", "autoTaskService"),
            customizableMySettings.map { it.key }.toSet(), setOf("unknown", "exit", "myMore", "autoTaskManage"))
        choices.forEach { selected ->
            val main = visibleMySettings(selected, false).map { it.key }.toSet()
            val more = visibleMySettings(selected, true).map { it.key }.toSet()
            assertTrue(main.intersect(more).isEmpty())
            assertEquals(mySettingItems.map { it.key }.toSet(), main + more)
            assertTrue("exit" !in more && "myMore" !in more)
        }
    }

    @Test fun unknownRestoredKeysDoNotCreateRowsOrLoseExistingRows() {
        assertEquals(visibleMySettings(defaultMyMoreItems, false), visibleMySettings(defaultMyMoreItems + "removed-key", false))
        assertEquals(visibleMySettings(defaultMyMoreItems, true), visibleMySettings(defaultMyMoreItems + "removed-key", true))
    }
}
