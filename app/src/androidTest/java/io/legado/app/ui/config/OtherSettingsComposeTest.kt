package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.model.settings.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class OtherSettingsComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun initial() = OtherSettingsState(loading = false, settings = OtherSettingsSnapshot())

    private fun actions() = OtherActions({ _, _ -> }, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {})

    private fun row(key: String): SemanticsNodeInteraction {
        compose
            .onNodeWithTag("other-settings-list")
            .performScrollToNode(hasTestTag("other-row-$key"))
        return compose.onNodeWithTag("other-row-$key")
    }

    @Test
    fun hiddenOnlyReadAppearsWithAutoRefreshAndNotificationSettingFollowsPlatformCapability() {
        var state by mutableStateOf(initial())
        compose.setContent {
            LegadoComposeTheme {
                OtherSettingsScreen(
                    state,
                    actions()
                        .copy(
                            boolean = { key, value ->
                                state =
                                    state.copy(
                                        settings =
                                            state.settings!!.copy(
                                                switches =
                                                    state.settings!!.switches + (key to value)
                                            )
                                    )
                            }
                        ),
                )
            }
        }
        compose.onNodeWithTag("other-row-onlyUpdateRead").assertDoesNotExist()
        row("auto_refresh").performClick()
        row("onlyUpdateRead").assertIsDisplayed().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("other-row-liveUpdateNotifications").assertDoesNotExist()
        compose.runOnIdle {
            state =
                state.copy(settings = state.settings!!.copy(promotedNotificationsVisible = true))
        }
        row("liveUpdateNotifications").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun configuredTokenRowShowsOnlyConfigurationStatusWhileThePrivateEditorSendsExactSelection() {
        var state by
            mutableStateOf(initial().copy(settings = OtherSettingsSnapshot(tokenConfigured = true)))
        val edits = mutableListOf<OtherEditor>()
        val values = mutableListOf<Triple<String, Int, Int>>()
        compose.setContent {
            LegadoComposeTheme {
                OtherSettingsScreen(
                    state,
                    actions()
                        .copy(
                            edit = { edits += it },
                            text = { text, start, end -> values += Triple(text, start, end) },
                        ),
                )
            }
        }
        row("jsSourceApiToken")
            .assertTextContains(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getString(R.string.js_source_api_token_configured),
                substring = true,
            )
            .performClick()
        assertEquals(listOf(OtherEditor.Token), edits)
        compose.runOnIdle {
            state = state.copy(draft = OtherSettingsDraft(editor = OtherEditor.Token))
        }
        compose.onNodeWithTag("other-editor-text").performTextReplacement("synthetic-private-token")
        assertEquals("synthetic-private-token", values.single().first)
    }

    @Test
    fun maximumLineCountPlusDoesNotOverflowAndPortEditorKeepsItsOriginalMinimum() {
        var state by
            mutableStateOf(
                initial()
                    .copy(
                        draft =
                            OtherSettingsDraft(
                                editor = OtherEditor.SourceLines,
                                text = Int.MAX_VALUE.toString(),
                            )
                    )
            )
        val values = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                OtherSettingsScreen(
                    state,
                    actions()
                        .copy(
                            text = { value, start, end ->
                                values += value
                                state =
                                    state.copy(
                                        draft =
                                            state.draft!!.copy(
                                                text = value,
                                                selectionStart = start,
                                                selectionEnd = end,
                                            )
                                    )
                            }
                        ),
                )
            }
        }
        compose.onNodeWithTag("other-number-plus").performClick()
        assertEquals(Int.MAX_VALUE.toString(), values.last())
        compose.onNodeWithTag("other-editor-text").performTextReplacement(Long.MAX_VALUE.toString())
        compose.onNodeWithTag("other-number-plus").performClick()
        assertEquals(Int.MAX_VALUE.toString(), values.last())
        compose.runOnIdle {
            state =
                state.copy(draft = OtherSettingsDraft(editor = OtherEditor.WebPort, text = "1024"))
        }
        compose.onNodeWithTag("other-number-minus").performClick()
        assertEquals("1024", values.last())
    }

    @Test
    fun languageAndHomeChoicesCommitTheExactPrimitiveValueOnRowClick() {
        var state by
            mutableStateOf(
                initial()
                    .copy(draft = OtherSettingsDraft(editor = OtherEditor.Language, text = "auto"))
            )
        val values = mutableListOf<String>()
        var confirmed = 0
        compose.setContent {
            LegadoComposeTheme {
                OtherSettingsScreen(
                    state,
                    actions()
                        .copy(text = { value, _, _ -> values += value }, confirm = { confirmed++ }),
                )
            }
        }
        compose.onNodeWithTag("other-choice-en").performClick()
        assertEquals(listOf("en"), values)
        assertEquals(1, confirmed)
        compose.runOnIdle {
            state =
                state.copy(
                    draft = OtherSettingsDraft(editor = OtherEditor.Home, text = "bookshelf")
                )
        }
        compose.onNodeWithTag("other-choice-rss").performClick()
        assertEquals(listOf("en", "rss"), values)
        assertEquals(2, confirmed)
        compose.onNodeWithTag("other-editor-ok").assertDoesNotExist()
    }

    @Test
    fun jsonEditorPreservesExactTextAndKeepsAllContentCompose() {
        val original = "{\"synthetic.test\":\"127.0.0.1\"}"
        var state by
            mutableStateOf(
                initial()
                    .copy(draft = OtherSettingsDraft(editor = OtherEditor.Hosts, text = original))
            )
        compose.setContent {
            LegadoComposeTheme {
                OtherSettingsScreen(
                    state,
                    actions()
                        .copy(
                            text = { value, start, end ->
                                state =
                                    state.copy(
                                        draft =
                                            state.draft!!.copy(
                                                text = value,
                                                selectionStart = start,
                                                selectionEnd = end,
                                            )
                                    )
                            }
                        ),
                )
            }
        }
        compose.onNodeWithTag("other-editor-text").assertTextContains(original, substring = true)
        compose.onNodeWithTag("other-editor-text").performTextReplacement("unfinished {")
        compose
            .onNodeWithTag("other-editor-text")
            .assertTextContains("unfinished {", substring = true)
        assertEquals("unfinished {", state.draft!!.text)
    }

    @Test
    fun destructiveMaintenanceRequiresExplicitConfirmationAndNativeDestinationsDispatchTheirExistingAction() {
        val destinations = mutableListOf<OtherDestination>()
        compose.setContent {
            LegadoComposeTheme {
                OtherSettingsScreen(initial(), actions().copy(destination = { destinations += it }))
            }
        }
        row("cleanCache").performClick()
        assertTrue(destinations.isEmpty())
        compose.onNodeWithTag("other-maintenance-confirm").performClick()
        assertEquals(listOf(OtherDestination.ClearCache), destinations)
        row("clearWebViewData").performClick()
        compose.onNodeWithTag("other-maintenance-confirm").performClick()
        row("shrinkDatabase").performClick()
        compose.onNodeWithTag("other-maintenance-confirm").performClick()
        row("videoSetting").performClick()
        row("checkSource").performClick()
        row("uploadRule").performClick()
        assertEquals(
            listOf(
                OtherDestination.ClearCache,
                OtherDestination.ClearWeb,
                OtherDestination.Shrink,
                OtherDestination.Video,
                OtherDestination.CheckSource,
                OtherDestination.Upload,
            ),
            destinations,
        )
    }

    @Test
    fun pendingAcceptedWriteKeepsRetryReachableInTheEditorAndCannotConfirmTwice() {
        var retried = 0
        val state =
            initial()
                .copy(
                    draft = OtherSettingsDraft(editor = OtherEditor.Token, text = "synthetic"),
                    pendingCommit = true,
                    error = "private write failed",
                )
        compose.setContent {
            LegadoComposeTheme { OtherSettingsScreen(state, actions().copy(retry = { retried++ })) }
        }
        compose.onNodeWithTag("other-editor-ok").assertIsNotEnabled()
        compose.onNodeWithTag("other-editor-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("other-editor-retry").performScrollTo().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun searchTargetsExactNumericRowAndDiscoveryFastScrollerDefaultStaysOptIn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var query by mutableStateOf<String?>(context.getString(R.string.source_edit_text_max_line))
        var opened = 0
        compose.setContent {
            LegadoComposeTheme {
                OtherSettingsScreen(
                    initial(),
                    actions().copy(edit = { opened++ }),
                    query,
                    { query = null },
                )
            }
        }
        compose.onNodeWithTag("other-search-sourceEditMaxLine").performScrollTo().performClick()
        compose.onNodeWithTag("other-row-sourceEditMaxLine").assertIsDisplayed()
        assertEquals(0, opened)
        row("showDiscoveryFastScroller")
            .assertIsOff()
            .assertTextContains(
                context.getString(R.string.show_discovery_fast_scroller),
                substring = true,
            )
    }
}
