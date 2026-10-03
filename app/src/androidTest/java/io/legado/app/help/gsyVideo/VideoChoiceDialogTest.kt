package io.legado.app.help.gsyVideo

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import io.legado.app.ui.about.AboutActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VideoChoiceDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun nativeWindowSelectionFinishesOnceThenDeliversSpeed() {
        val calls = mutableListOf<String>()
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ChoiceSpeedDialog(activity).apply {
                    initList(
                        listOf(3f, 1f),
                        object : ChoiceSpeedDialog.OnListItemClickListener {
                            override fun onItemClick(value: Float) {
                                calls += "speed:$value"
                            }

                            override fun finishDialog() {
                                calls += "finished"
                            }
                        },
                    )
                    show()
                }
            }
            compose.onNodeWithTag("video-choice-1").performClick()
            compose.runOnIdle { assertEquals(listOf("finished", "speed:1.0"), calls) }
        }
    }

    @Test
    fun cancellationOnlyFinishesAndDoesNotSelect() {
        val calls = mutableListOf<String>()
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ChoiceSpeedDialog(activity).apply {
                    initList(
                        listOf(3f),
                        object : ChoiceSpeedDialog.OnListItemClickListener {
                            override fun onItemClick(value: Float) {
                                calls += "speed:$value"
                            }

                            override fun finishDialog() {
                                calls += "finished"
                            }
                        },
                    )
                    show()
                    cancel()
                }
            }
            compose.runOnIdle { assertEquals(listOf("finished"), calls) }
        }
    }
}
