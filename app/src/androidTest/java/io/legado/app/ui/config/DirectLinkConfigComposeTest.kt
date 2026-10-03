package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class DirectLinkConfigComposeTest {
    @get:Rule val compose = createComposeRule()
    private val draft = DirectLinkDraft("upload", "$.url", "Fixture", false, "0")
    private val state =
        mutableStateOf(
            DirectLinkConfigState(
                loading = false,
                session = DirectLinkSession(UUID.randomUUID().toString(), draft),
                defaults = listOf(draft.copy(summary = "Default")),
            )
        )

    @Test
    fun fieldsWholeCheckboxAndExpiryValidationUseTypedDraftWithoutSaving() {
        var changes = 0
        compose.setContent {
            LegadoComposeTheme {
                DirectLinkConfigScreen(
                    state.value,
                    DirectLinkConfigActions(
                        edit = {
                            changes++
                            state.value =
                                state.value.copy(
                                    session =
                                        state.value.session!!.copy(
                                            draft = it(state.value.session!!.draft)
                                        )
                                )
                        }
                    ),
                )
            }
        }
        compose.onNodeWithTag("direct-link-compress").performClick()
        compose.onNodeWithTag("direct-link-compress").assertIsOn()
        compose.onNodeWithTag("direct-link-expiry").performTextReplacement("36500")
        compose.runOnIdle {
            assertEquals("36500", state.value.session!!.draft.expiry)
            assertEquals(2, changes)
            state.value = state.value.copy(issue = DirectLinkIssue.Expiry)
        }
        compose.onNodeWithTag("direct-link-validation").assertExists()
    }

    @Test
    fun menuKeepsCopyPasteAndDefaultSelectionOrderAndResultCopyAction() {
        val actions = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                DirectLinkConfigScreen(
                    state.value,
                    DirectLinkConfigActions(
                        copy = { actions += "copy" },
                        paste = { actions += "paste" },
                        preset = { actions += "preset:$it" },
                        copyResult = { actions += "result" },
                    ),
                )
            }
        }
        compose.onNodeWithTag("direct-link-menu").performClick()
        compose.onNodeWithTag("direct-link-copy").performClick()
        compose.onNodeWithTag("direct-link-menu").performClick()
        compose.onNodeWithTag("direct-link-paste").performClick()
        compose.onNodeWithTag("direct-link-menu").performClick()
        compose.onNodeWithTag("direct-link-defaults").performClick()
        compose.onNodeWithTag("direct-link-preset-0").performClick()
        compose.runOnIdle {
            state.value =
                state.value.copy(session = state.value.session!!.copy(result = "Full result"))
        }
        compose.onNodeWithTag("direct-link-result").assertTextEquals("Full result")
        compose.onNodeWithTag("direct-link-result-copy").performClick()
        assertEquals(listOf("copy", "paste", "preset:0", "result"), actions)
    }

    @Test
    fun saveDisablesEditingAndClosingWhileTestRemainsExplicitlyCancelable() {
        var stopped = 0
        compose.setContent {
            LegadoComposeTheme {
                DirectLinkConfigScreen(
                    state.value,
                    DirectLinkConfigActions(cancelTest = { stopped++ }),
                )
            }
        }
        compose.runOnIdle { state.value = state.value.copy(saving = true) }
        compose.onNodeWithTag("direct-link-save").assertIsNotEnabled()
        compose.onNodeWithTag("direct-link-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("direct-link-upload").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(saving = false, testing = true) }
        compose.onNodeWithTag("direct-link-test").assertIsEnabled().performClick()
        assertEquals(1, stopped)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle
            get() = registry
    }

    @Test
    fun restoredSuccessfulSaveWaitsForResumedAndReleasesBeforeOneClose() {
        lateinit var owner: Owner
        lateinit var model: DirectLinkConfigViewModel
        var releases = 0
        var closes = 0
        val repo =
            object : DirectLinkConfigRepository {
                override suspend fun open(id: String?) =
                    DirectLinkSession(UUID.randomUUID().toString(), draft, finished = true)

                override suspend fun defaults() = emptyList<DirectLinkDraft>()

                override suspend fun write(value: DirectLinkSession) = Unit

                override suspend fun save(draft: DirectLinkDraft) = Unit

                override suspend fun test(draft: DirectLinkDraft) = "result"

                override suspend fun release(id: String) {
                    releases++
                }
            }
        compose.runOnIdle {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = DirectLinkConfigViewModel(repo, SavedStateHandle())
        }
        try {
            compose.setContent {
                LegadoComposeTheme {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        DirectLinkConfigRoute(
                            model,
                            { true },
                            {
                                assertEquals(1, releases)
                                closes++
                            },
                            {},
                            { null },
                            {},
                        )
                    }
                }
            }
            compose.waitUntil { !model.state.value.loading }
            compose.waitForIdle()
            assertEquals(0, closes)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { closes == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitForIdle()
            assertEquals(1, closes)
        } finally {
            compose.runOnIdle { model.stop() }
        }
    }
}
