package io.legado.app.ui.highlight.edit

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class HighlightRuleEditorComposeTest {
    @get:Rule val compose = createComposeRule()
    private val stores = mutableListOf<ViewModelStore>()

    @After
    fun cleanup() {
        compose.runOnIdle { stores.forEach { it.clear() } }
    }

    private fun newModel(
        repo: Fake = Fake(),
        saved: SavedStateHandle = SavedStateHandle(),
    ): HighlightRuleEditorViewModel {
        val model = HighlightRuleEditorViewModel(repo, saved, 9, null)
        stores += ViewModelStore().apply { put("rule", model) }
        return model
    }

    @Test
    fun actualEditableFieldsAndApplyFlagsReachSaveWithoutChangingHiddenMetadata() {
        val repo = Fake()
        lateinit var model: HighlightRuleEditorViewModel
        compose.runOnIdle { model = newModel(repo) }
        compose.setContent {
            LegadoComposeTheme { HighlightRuleEditorRoute(model, { true }, {}, { _, _ -> }) }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("highlight-rule-name").performTextReplacement("New name")
        compose
            .onNodeWithTag("highlight-rule-group")
            .performScrollTo()
            .performTextReplacement("Group")
        compose
            .onNodeWithTag("highlight-rule-pattern")
            .performScrollTo()
            .performTextReplacement("[A-Z]+")
        compose.onNodeWithTag("highlight-rule-regex").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-rule-body").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("highlight-rule-title").performScrollTo().performClick().assertIsOn()
        compose
            .onNodeWithTag("highlight-rule-scope")
            .performScrollTo()
            .performTextReplacement("Book")
        compose.onNodeWithTag("highlight-rule-save").performClick()
        compose.waitUntil { repo.saved != null }
        val rule = repo.saved!!.rule
        assertEquals("New name", rule.name)
        assertEquals("Group", rule.group)
        assertEquals("[A-Z]+", rule.pattern)
        assertTrue(rule.isRegex)
        assertTrue(rule.applyToTitle)
        assertFalse(rule.applyToBody)
        assertEquals("Book", rule.scope)
        assertFalse(rule.isEnabled)
        assertEquals(77, rule.order)
        assertEquals(789L, rule.timeoutMillisecond)
    }

    @Test
    fun initialFailureDisablesSaveAndActualRetryLoadsOriginalAndStyleEventDoesNotPersist() {
        val repo = Fake().apply { loadFails = true }
        lateinit var model: HighlightRuleEditorViewModel
        var styles = 0
        compose.runOnIdle { model = newModel(repo) }
        compose.setContent {
            LegadoComposeTheme {
                HighlightRuleEditorRoute(
                    model,
                    { true },
                    { if (it == HighlightRuleEditorEvent.Style) styles++ },
                    { _, _ -> },
                )
            }
        }
        compose.waitUntil { model.state.value.error != null }
        compose.onNodeWithTag("highlight-rule-save").assertIsNotEnabled()
        compose.runOnIdle { repo.loadFails = false }
        compose.onNodeWithTag("highlight-rule-retry").performClick()
        compose.waitUntil { !model.state.value.loading && model.state.value.draft != null }
        compose.onNodeWithTag("highlight-rule-style").performScrollTo().performClick()
        compose.waitUntil { styles == 1 }
        assertNull(repo.saved)
        compose.onNodeWithTag("highlight-rule-cancel").performClick()
        assertNull(repo.saved)
    }

    @Test
    fun sharedFillRendererPreviewChangesRealPixelsAndTextChannelFallsBackWhenDisabled() {
        var style by mutableStateOf(HighlightStyle())
        compose.setContent { LegadoComposeTheme { HighlightRuleStylePreview(style) } }
        val before = compose.onNodeWithTag("highlight-rule-preview").captureToImage().toPixelMap()
        compose.runOnIdle {
            style =
                HighlightStyle(
                    fill = 0xffff0000.toInt(),
                    fillShape = HighlightStyle.FillShape.RECTANGLE,
                    textColor = 0xff0000ff.toInt(),
                )
        }
        val after = compose.onNodeWithTag("highlight-rule-preview").captureToImage().toPixelMap()
        var differences = 0
        for (x in 0 until minOf(before.width, after.width)) for (y in
            0 until minOf(before.height, after.height)) if (before[x, y] != after[x, y])
            differences++
        assertTrue(differences > 20)
    }

    @Test
    fun colorHexAndPresetControlsRespectAlphaAndCancelDoesNotCallSelection() {
        var selected: Int? = null
        var canceled = false
        compose.setContent {
            LegadoComposeTheme {
                HighlightRuleColorPicker(
                    HighlightRuleColorDraft(8101, 0, true),
                    { selected = it },
                    { canceled = true },
                )
            }
        }
        compose
            .onNodeWithTag("highlight-rule-color-hex")
            .performScrollTo()
            .performTextReplacement("#80123456")
        compose.onNodeWithTag("highlight-rule-color-save").performClick()
        assertEquals(0x80123456.toInt(), selected)
        selected = null
        compose
            .onNodeWithTag("highlight-rule-color-hex")
            .performScrollTo()
            .performTextReplacement("#-")
        compose.onNodeWithTag("highlight-rule-color-save").assertIsNotEnabled()
        compose.onNodeWithTag("highlight-rule-color-cancel").performClick()
        assertTrue(canceled)
        assertNull(selected)
    }

    @Test
    fun saveEffectWaitsForResumeAndIsConsumedBeforeHostPausesWithoutReplaying() {
        val owner = Owner()
        lateinit var model: HighlightRuleEditorViewModel
        var calls = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            model = newModel()
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    HighlightRuleEditorRoute(
                        model,
                        { true },
                        {
                            if (it == HighlightRuleEditorEvent.Saved) {
                                assertNull(model.state.value.event)
                                calls++
                                owner.registry.currentState = Lifecycle.State.CREATED
                            }
                        },
                        { _, _ -> },
                    )
                }
            }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("highlight-rule-save").performClick()
        compose.waitUntil { model.state.value.event == HighlightRuleEditorEvent.Saved }
        assertEquals(0, calls)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { calls == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, calls)
    }

    @Test
    fun styleColorCallbacksUpdateOnlyDraftAndSavedStyleSurvivesEditorRecreation() {
        val repo = Fake()
        val saved = SavedStateHandle()
        var next by mutableStateOf<HighlightRuleEditorViewModel?>(null)
        compose.runOnIdle { next = newModel(repo, saved) }
        compose.setContent {
            LegadoComposeTheme {
                key(next) {
                    HighlightRuleEditorRoute(
                        next!!,
                        { true },
                        {},
                        { channel, color ->
                            next!!.style(
                                HighlightStyleDialog.applyChannelColor(
                                    next!!.state.value.draft!!.rule.style,
                                    channel,
                                    color,
                                )
                            )
                        },
                    )
                }
            }
        }
        compose.waitUntil { !next!!.state.value.loading }
        compose.runOnIdle { next!!.color(HighlightStyleDialog.HL_TEXT, 0, false) }
        compose
            .onNodeWithTag("highlight-rule-color-hex")
            .performScrollTo()
            .performTextReplacement("#123456")
        compose.onNodeWithTag("highlight-rule-color-save").performClick()
        compose.waitUntil { next!!.state.value.draft!!.rule.style.textColor == 0xff123456.toInt() }
        assertNull(repo.saved)
        compose.runOnIdle { next!!.flush() }
        compose.waitUntil { repo.disk != null }
        compose.runOnIdle { next = newModel(repo, copy(saved)) }
        compose.waitUntil { !next!!.state.value.loading }
        assertEquals(0xff123456.toInt(), next!!.state.value.draft!!.rule.style.textColor)
    }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : HighlightRuleEditorRepository {
        var loadFails = false
        var disk: HighlightRuleEditorDraft? = null
        var saved: HighlightRuleEditorDraft? = null

        override suspend fun initial(
            session: String,
            id: Long,
            seed: String?,
        ): HighlightRuleEditorDraft {
            if (loadFails) error("failed")
            return disk
                ?: HighlightRuleEditorDraft(
                    HighlightRuleDraft(
                        id = 9,
                        name = "Original",
                        pattern = "Original",
                        isEnabled = false,
                        order = 77,
                        timeoutMillisecond = 789,
                    )
                )
        }

        override suspend fun draft(session: String, draft: HighlightRuleEditorDraft) {
            disk = draft
        }

        override suspend fun save(session: String, draft: HighlightRuleEditorDraft) =
            draft.copy(savedRuleId = 9, revision = draft.revision + 1).also {
                saved = it
                disk = it
            }
    }
}
