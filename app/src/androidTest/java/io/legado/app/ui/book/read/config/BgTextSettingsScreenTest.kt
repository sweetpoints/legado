package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.data.preferences.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BgTextSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val svg =
        """<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48"><rect width="48" height="48" fill="#f00"/></svg>"""

    private fun initial() =
        BgTextSettingsState(
            BgTextSettingsSnapshot(
                "read:0:day",
                "Preset",
                textColor = 0xff000000.toInt(),
                templates = listOf(BgTextTemplate("Red template", svg)),
            ),
            assets = listOf("paper.png", "night.jpg"),
            defaults = listOf(BgTextPreset("Default", "{}")),
            loadingAssets = false,
        )

    @Composable
    private fun Content(
        state: BgTextSettingsState,
        onIntent: (BgTextSettingsIntent) -> Unit,
        actualSvg: Boolean = false,
    ) {
        BgTextSettingsScreen(
            state,
            onIntent,
            Color.White,
            Color.Black,
            Color.DarkGray,
            Modifier.width(360.dp).heightIn(max = 600.dp),
            assetPreview = { _, bounds -> Box(bounds) },
            svgPreview = { svg, bounds ->
                if (actualSvg) BgTextSvgPreview(svg, bounds.testTag("real-bg-svg-preview"))
                else Box(bounds)
            },
        )
    }

    @Test
    fun imageBookHidesAllUnderlineControlsAndReviewColorLongPressHasOwnAction() {
        val state = initial().copy(settings = initial().settings.copy(imageBook = true))
        val intents = mutableListOf<BgTextSettingsIntent>()
        compose.setContent { LegadoComposeTheme { Content(state, { intents += it }) } }
        listOf(
                "bg-underline-mode",
                "bg-underline-body",
                "bg-underline-title",
                "bg-slider-Width",
                "bg-slider-Distance",
                "bg-color-Underline",
            )
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag("bg-slider-Alpha").performScrollTo().assertExists()
        compose.onNodeWithTag("bg-color-Review").performScrollTo().performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(listOf(BgTextSettingsIntent.ResetReviewColor), intents) }
    }

    @Test
    fun underlineWidthAndDistanceHaveHalfDpMicrostepsAndDisabledEndpoints() {
        var state by mutableStateOf(initial())
        val intents = mutableListOf<BgTextSettingsIntent>()
        compose.setContent {
            LegadoComposeTheme {
                Content(
                    state,
                    { intent ->
                        intents += intent
                        if (intent is BgTextSettingsIntent.Slider) {
                            val settings =
                                when (intent.slider) {
                                    BgTextSlider.Width ->
                                        state.settings.copy(underlineWidth = intent.value)
                                    BgTextSlider.Distance ->
                                        state.settings.copy(underlineDistance = intent.value)
                                    BgTextSlider.Alpha -> state.settings.copy(alpha = intent.value)
                                }
                            state = state.copy(settings = settings)
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("bg-value-Width").performScrollTo().assertTextEquals("1.0dp")
        compose.onNodeWithTag("bg-plus-Width").performScrollTo().performClick()
        compose.onNodeWithTag("bg-value-Width").assertTextEquals("1.5dp")
        compose.onNodeWithTag("bg-slider-Width").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(0f)
        }
        compose.onNodeWithTag("bg-minus-Width").assertIsNotEnabled()
        compose.onNodeWithTag("bg-slider-Width").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(20f)
        }
        compose.onNodeWithTag("bg-plus-Width").assertIsNotEnabled()
        compose.onNodeWithTag("bg-value-Width").assertTextEquals("10.0dp")
        compose.onNodeWithTag("bg-slider-Distance").performScrollTo().performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(60f)
        }
        compose.onNodeWithTag("bg-value-Distance").assertTextEquals("30.0dp")
        compose.runOnIdle {
            assertEquals(BgTextSettingsIntent.Slider(BgTextSlider.Width, 3), intents.first())
        }
    }

    @Test
    fun modeReflectionDoesNotInvokeCallbacksAndAllSevenChoicesAndSwitchesRemain() {
        var state by mutableStateOf(initial())
        val intents = mutableListOf<BgTextSettingsIntent>()
        compose.setContent { LegadoComposeTheme { Content(state, { intents += it }) } }
        compose.runOnIdle {
            state = state.copy(settings = state.settings.copy(underlineMode = 6))
            assertTrue(intents.isEmpty())
        }
        compose.onNodeWithTag("bg-underline-body").performScrollTo().assertIsOn().performClick()
        compose.runOnIdle {
            assertEquals(BgTextSettingsIntent.UnderlineBody(false), intents.last())
            state =
                state.copy(
                    editor = BgTextEditor(BgTextEditorKind.UnderlineMode, state.settings.context)
                )
        }
        (0..6).forEach { compose.onNodeWithTag("bg-select-$it").assertExists() }
        compose.onNodeWithTag("bg-select-6").performClick()
        compose.runOnIdle { assertEquals(BgTextSettingsIntent.UnderlineMode(6), intents.last()) }
    }

    @Test
    fun colorEditorShowsUnderlineAlphaAndOtherColorsOnlyRgbWithCancelableDraft() {
        var state by
            mutableStateOf(
                initial()
                    .copy(
                        editor =
                            BgTextEditor(
                                BgTextEditorKind.Color,
                                "read:0:day",
                                "80FF0000",
                                color = BgTextColor.Underline,
                            )
                    )
            )
        val intents = mutableListOf<BgTextSettingsIntent>()
        compose.setContent { LegadoComposeTheme { Content(state, { intents += it }) } }
        compose.onNodeWithTag("bg-color-channel-0").performScrollTo().performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(64f)
        }
        compose.runOnIdle {
            assertEquals(BgTextSettingsIntent.ColorChannel(0, 64), intents.last())
            state = state.copy(editor = state.editor!!.copy(text = "40FF0000"))
        }
        compose.onNodeWithTag("bg-editor-input").assertTextContains("40FF0000")
        compose.onNodeWithTag("bg-editor-cancel").performClick()
        compose.runOnIdle {
            assertEquals(BgTextSettingsIntent.CancelEditor, intents.last())
            state =
                state.copy(editor = state.editor!!.copy(text = "FF0000", color = BgTextColor.Text))
        }
        compose.onNodeWithTag("bg-color-channel-0").assertDoesNotExist()
        (1..3).forEach { compose.onNodeWithTag("bg-color-channel-$it").assertExists() }
    }

    @Test
    fun templateGridHasActualSvgPixelsClickLongPressRenameAndDeleteContracts() {
        var state by
            mutableStateOf(
                initial().copy(editor = BgTextEditor(BgTextEditorKind.Templates, "read:0:day"))
            )
        val intents = mutableListOf<BgTextSettingsIntent>()
        compose.setContent {
            LegadoComposeTheme { Content(state, { intents += it }, actualSvg = true) }
        }
        compose.waitUntil {
            compose
                .onAllNodesWithTag("real-bg-svg-preview", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val pixels =
            compose
                .onNodeWithTag("real-bg-svg-preview", useUnmergedTree = true)
                .captureToImage()
                .toPixelMap()
        assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
        compose
            .onNodeWithTag("bg-template-0")
            .assertContentDescriptionContains("Red template")
            .performClick()
        compose.onNodeWithTag("bg-template-0").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(
                BgTextSettingsIntent.ApplyTemplate(state.settings.templates.single()),
                intents.first(),
            )
            assertEquals(
                BgTextSettingsIntent.DeleteTemplateEditor(state.settings.templates.single()),
                intents.last(),
            )
            state =
                state.copy(
                    editor =
                        BgTextEditor(
                            BgTextEditorKind.DeleteTemplate,
                            "read:0:day",
                            "Red template",
                            svg = svg,
                        )
                )
        }
        compose.onNodeWithTag("bg-template-rename").performClick()
        compose.runOnIdle {
            assertEquals(
                BgTextSettingsIntent.SaveTemplate("", state.settings.templates.single()),
                intents.last(),
            )
        }
        compose.onNodeWithTag("bg-template-delete-confirm").performClick()
        compose.runOnIdle { assertEquals(BgTextSettingsIntent.DeleteTemplate, intents.last()) }
    }

    @Test
    fun nameAndSvgEditorKeepTextThroughComposeRestorationWithoutConfirmingIt() {
        var state by
            mutableStateOf(
                initial().copy(editor = BgTextEditor(BgTextEditorKind.Svg, "read:0:day", svg))
            )
        val intents = mutableListOf<BgTextSettingsIntent>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LegadoComposeTheme {
                Content(
                    state,
                    { intent ->
                        intents += intent
                        if (intent is BgTextSettingsIntent.Text)
                            state =
                                state.copy(
                                    editor =
                                        state.editor!!.copy(
                                            text = intent.text,
                                            start = intent.start,
                                            end = intent.end,
                                        )
                                )
                    },
                )
            }
        }
        compose.onNodeWithTag("bg-editor-input").performTextReplacement("Draft SVG")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bg-editor-input").assertTextContains("Draft SVG")
        compose.runOnIdle {
            assertTrue(intents.none { it == BgTextSettingsIntent.ConfirmEditor })
            state =
                state.copy(editor = BgTextEditor(BgTextEditorKind.Name, "read:0:day", "Name draft"))
        }
        compose.onNodeWithTag("bg-editor-input").assertTextContains("Name draft")
        compose.onNodeWithTag("bg-editor-cancel").performClick()
        compose.runOnIdle { assertEquals(BgTextSettingsIntent.CancelEditor, intents.last()) }
    }

    @Test
    fun unrelatedFileWorkKeepsEditsAvailableWhileSvgValidationDisablesRepeatedConfirm() {
        var state by
            mutableStateOf(
                initial()
                    .copy(
                        editor = BgTextEditor(BgTextEditorKind.Name, "read:0:day", "Draft"),
                        work =
                            BgTextWork(
                                1,
                                BgTextWorkKind.ImportFile,
                                "content://archive",
                                "read:0:day",
                                0,
                            ),
                    )
            )
        val intents = mutableListOf<BgTextSettingsIntent>()
        compose.setContent { LegadoComposeTheme { Content(state, { intents += it }) } }
        compose.onNodeWithTag("bg-editor-confirm").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(BgTextSettingsIntent.ConfirmEditor), intents)
            state =
                state.copy(
                    editor = BgTextEditor(BgTextEditorKind.Svg, "read:0:day", svg),
                    work = state.work!!.copy(kind = BgTextWorkKind.SvgEdit),
                )
        }
        compose.onNodeWithTag("bg-editor-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("bg-editor-cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(BgTextSettingsIntent.CancelEditor, intents.last()) }
    }

    @Test
    fun importsExportsDeletionAndBackgroundPickerKeepDistinctActionsAndAssetName() {
        val state = initial()
        val intents = mutableListOf<BgTextSettingsIntent>()
        compose.setContent { LegadoComposeTheme { Content(state, { intents += it }) } }
        compose.onNodeWithTag("bg-import").performScrollTo().performClick()
        compose.onNodeWithTag("bg-export").performClick()
        compose.onNodeWithTag("bg-delete").performClick()
        compose.onNodeWithTag("bg-pick-image").performScrollTo().performClick()
        compose.onNodeWithTag("bg-asset-paper.png").assertTextContains("paper").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    BgTextSettingsIntent.Picker(BgTextAction.PickImport),
                    BgTextSettingsIntent.Picker(BgTextAction.PickExport),
                    BgTextSettingsIntent.DeletePreset,
                    BgTextSettingsIntent.Picker(BgTextAction.PickBackground),
                    BgTextSettingsIntent.Asset("paper.png"),
                ),
                intents,
            )
        }
    }
}
