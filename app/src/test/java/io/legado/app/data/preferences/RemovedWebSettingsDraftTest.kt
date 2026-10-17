package io.legado.app.data.preferences

import io.legado.app.model.settings.*
import org.junit.Assert.*
import org.junit.Test

class RemovedWebSettingsDraftTest {
    @Test
    fun currentDraftRoundTripsWithoutChangingAnyFields() {
        val draft = OtherSettingsDraft(revision = 9, editor = OtherEditor.McpPort, text = "1236")
        assertEquals(draft, decodeOtherSettingsDraft(io.legado.app.utils.GSON.toJson(draft)))
    }

    @Test
    fun removedPortDraftDoesNotRestoreOrApplyButMcpEffectsRemain() {
        val value = decodeOtherSettingsDraft("""{
            "revision":42,"editor":"WebPort","text":"1122",
            "selectionStart":1,"selectionEnd":3,
            "mutation":{"id":"old","kind":"Number","key":"WebPort","number":1122},
            "effects":[{"id":"web","effect":"RestartWeb"},{"id":"mcp","effect":"RestartMcp"}]
        }""")
        assertEquals(42L, value.revision)
        assertNull(value.editor)
        assertEquals("", value.text)
        assertNull(value.mutation)
        assertEquals(listOf(OtherEffectReceipt("mcp", OtherEffect.RestartMcp)), value.effects)
    }

    @Test
    fun sharedTokenDraftKeepsCredentialsAndOnlyTheRemainingMcpEffect() {
        val value = decodeOtherSettingsDraft("""{
            "revision":7,"editor":"Token","text":"synthetic",
            "mutation":{"id":"token","kind":"Text","key":"Token","text":"synthetic",
                "effects":["RestartWeb","RestartMcp"]},"effects":[]
        }""")
        assertEquals(OtherEditor.Token, value.editor)
        assertEquals("synthetic", value.mutation!!.text)
        assertEquals(listOf(OtherEffect.RestartMcp), value.mutation.effects)
    }
}
