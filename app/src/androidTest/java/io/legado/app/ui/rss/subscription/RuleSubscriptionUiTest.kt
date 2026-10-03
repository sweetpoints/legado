package io.legado.app.ui.rss.subscription

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class RuleSubscriptionUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<RuleSubscriptionViewModel>()

    @After
    fun after() {
        compose.runOnIdle { models.forEach { it.stop() } }
    }

    private fun show(rules: SubscriptionRules = SubscriptionRules()): RuleSubscriptionViewModel {
        lateinit var model: RuleSubscriptionViewModel
        compose.runOnIdle {
            model = RuleSubscriptionViewModel(rules, SubscriptionDrafts(rules), SavedStateHandle())
            models += model
        }
        compose.setContent {
            LegadoComposeTheme { RuleSubscriptionRoute(model, {}, {}, { error(it) }) }
        }
        compose.waitUntil { model.state.value.loaded }
        return model
    }

    @Test
    fun popupKeepsOnlyDeleteAndDeletesExactRowWhileEditDoesNotOpenImport() {
        val rules = SubscriptionRules()
        val model = show(rules)
        compose.onNodeWithTag("subscription-edit-2").performClick()
        compose.waitUntil { model.state.value.editor != null }
        assertEquals(2L, model.state.value.editor!!.id)
        assertNull(model.state.value.navigation)
        compose.onNodeWithTag("subscription-cancel").performClick()
        compose.onNodeWithTag("subscription-menu-2").performClick()
        compose.onNodeWithTag("subscription-delete-2").performClick()
        compose.waitUntil { model.state.value.rows.size == 2 }
        assertEquals(listOf(1L, 3L), model.state.value.rows.map { it.id })
        assertEquals(0, rules.saves)
    }

    @Test
    fun completeFormEditsTypeNameUrlAndAutomaticSilent24HoursAndPersistsOnlyAfterOk() {
        val rules = SubscriptionRules()
        val model = show(rules)
        compose.onNodeWithTag("subscription-add").performClick()
        compose.onNodeWithTag("subscription-type").performClick()
        compose.onNodeWithTag("subscription-type-2").performClick()
        compose.onNodeWithTag("subscription-name").performTextInput("New subscription")
        compose.onNodeWithTag("subscription-url").performTextInput("https://new")
        compose.onNodeWithTag("subscription-silent").assertIsNotEnabled()
        compose.onNodeWithTag("subscription-auto").performClick()
        compose.onNodeWithTag("subscription-interval").assertTextContains("24")
        compose.onNodeWithTag("subscription-silent").performClick()
        assertEquals(0, rules.saves)
        compose.onNodeWithTag("subscription-save").performClick()
        compose.waitUntil { model.state.value.editor == null }
        val value = rules.flow.value.last()
        assertEquals("New subscription", value.name)
        assertEquals("https://new", value.url)
        assertEquals(2, value.type)
        assertTrue(value.automatic)
        assertTrue(value.silent)
        assertEquals(24, value.interval)
    }

    @Test
    fun validationFailureLeavesEditableInputAndZeroIntervalUnchecksBothOptions() {
        val model = show()
        compose.onNodeWithTag("subscription-add").performClick()
        compose.onNodeWithTag("subscription-save").performClick()
        compose.waitUntil { model.state.value.issue == RuleSubscriptionIssue.EmptyUrl }
        compose.onNodeWithTag("subscription-editor").assertExists()
        compose.onNodeWithTag("subscription-url").assertIsEnabled()
        compose.onNodeWithTag("subscription-auto").performClick()
        compose.onNodeWithTag("subscription-silent").performClick()
        compose.onNodeWithTag("subscription-interval").performTextReplacement("0")
        compose.onNodeWithTag("subscription-auto").assertIsOff()
        compose.onNodeWithTag("subscription-silent").assertIsOff()
        compose.onNodeWithTag("subscription-silent").assertIsNotEnabled()
    }

    @Test
    fun actualTouchCancelRestoresRoomBaselineAndNormalReleaseCommitsMovedOrder() {
        val rules = SubscriptionRules()
        val model = show(rules)
        val a = compose.onNodeWithTag("subscription-row-1").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("subscription-row-3").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("subscription-row-1").performTouchInput {
            down(Offset(24f, center.y))
            advanceEventTime(700)
            moveBy(Offset(0f, c.center.y - a.center.y), 400)
            cancel()
        }
        compose.runOnIdle {
            assertEquals(listOf(1L, 2L, 3L), model.state.value.rows.map { it.id })
            assertTrue(rules.orders.isEmpty())
            assertNull(model.state.value.navigation)
        }
        compose.onNodeWithTag("subscription-row-1").performTouchInput {
            down(Offset(24f, center.y))
            advanceEventTime(700)
            moveBy(Offset(0f, c.center.y - a.center.y), 400)
            up()
        }
        compose.waitUntil { rules.orders.isNotEmpty() }
        assertEquals(listOf(2L, 3L, 1L), rules.orders.single())
        assertNull(model.state.value.navigation)
    }

    @Test
    fun customAccessibilityMoveCommitsEquivalentOrder() {
        val rules = SubscriptionRules()
        show(rules)
        val actions =
            compose
                .onNodeWithTag("subscription-row-1")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single().action()) }
        compose.waitUntil { rules.orders.isNotEmpty() }
        assertEquals(listOf(2L, 1L, 3L), rules.orders.single())
    }

    @Test
    fun saveableCursorAndTypePopupRestoreWithoutPersistingTextInsideScreenSavedState() {
        val tester = StateRestorationTester(compose)
        var state by
            mutableStateOf(
                RuleSubscriptionState(
                    loading = false,
                    loaded = true,
                    editor =
                        RuleSubscriptionEditor(
                            newId = 10,
                            name = "Original name",
                            url = "https://original",
                        ),
                )
            )
        var typeChanges = 0
        tester.setContent {
            LegadoComposeTheme {
                RuleSubscriptionScreen(
                    state,
                    RuleSubscriptionActions(
                        name = { state = state.copy(editor = state.editor!!.copy(name = it)) },
                        type = {
                            typeChanges++
                            state = state.copy(editor = state.editor!!.copy(type = it))
                        },
                    ),
                )
            }
        }
        compose.onNodeWithTag("subscription-name").performTextInputSelection(TextRange(2, 5))
        compose.onNodeWithTag("subscription-type").performClick()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("subscription-type-1").assertExists()
        assertEquals(0, typeChanges)
        compose.onNodeWithTag("subscription-type-1").performClick()
        assertEquals(
            TextRange(2, 5),
            compose
                .onNodeWithTag("subscription-name")
                .fetchSemanticsNode()
                .config[SemanticsProperties.TextSelectionRange],
        )
        assertEquals("Original name", state.editor!!.name)
        assertEquals(1, typeChanges)
    }

    @Test
    fun darkSmallScreenEditorScrollsToIntervalAndRetainsVisibleSaveAndReadableText() {
        val editor =
            RuleSubscriptionEditor(
                newId = 10,
                name = "Small dark editor",
                url = "url",
                automatic = true,
                interval = "24",
                silentEnabled = true,
            )
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 320
                    screenHeightDp = 300
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.size(320.dp, 300.dp)) {
                        RuleSubscriptionScreen(
                            RuleSubscriptionState(loading = false, loaded = true, editor = editor),
                            RuleSubscriptionActions(),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("subscription-interval").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("subscription-save").assertIsDisplayed()
        val image = compose.onNodeWithTag("subscription-editor").captureToImage().toPixelMap()
        assertTrue(
            (0 until image.width).any { x ->
                (0 until image.height).any { y ->
                    val color = image[x, y]
                    color.red > .7f && color.green > .7f && color.blue > .7f
                }
            }
        )
    }
}
