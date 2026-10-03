package io.legado.app.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupActionStateTest {
    @Test
    fun disabledItemFlagAndValueBothOverrideCheckableOrDangerPresentation() {
        val checked =
            PopupAction.PopupActionItem("Checked", "checked", checkable = true, checked = true)
        val disabled = checked.copy(enabled = false)
        assertTrue(popupItemEnabled(checked, emptySet()))
        assertFalse(popupItemEnabled(checked, setOf("checked")))
        assertFalse(popupItemEnabled(disabled, emptySet()))
        assertFalse(popupItemEnabled(disabled, setOf("checked")))
    }
}
