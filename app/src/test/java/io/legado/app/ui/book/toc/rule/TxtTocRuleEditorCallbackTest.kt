package io.legado.app.ui.book.toc.rule

import io.legado.app.data.entities.TxtTocRule
import org.junit.Assert.*
import org.junit.Test

class TxtTocRuleEditorCallbackTest {
    @Test
    fun parentCallbackTakesPriorityAndActivityIsFallback() {
        var parentCalls = 0
        var activityCalls = 0
        val parent =
            object : TxtTocRuleEditDialog.Callback {
                override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
                    parentCalls++
                }
            }
        val activity =
            object : TxtTocRuleEditDialog.Callback {
                override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
                    activityCalls++
                }
            }
        val rule = TxtTocRule(id = 123)
        txtTocRuleEditorCallback(parent, activity)?.saveTxtTocRule(rule)
        assertEquals(1, parentCalls)
        assertEquals(0, activityCalls)
        txtTocRuleEditorCallback(Any(), activity)?.saveTxtTocRule(rule)
        assertEquals(1, activityCalls)
        assertNull(txtTocRuleEditorCallback(null, Any()))
    }
}
