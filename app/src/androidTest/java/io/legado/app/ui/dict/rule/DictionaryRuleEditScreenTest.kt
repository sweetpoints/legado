package io.legado.app.ui.dict.rule

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.google.gson.JsonParser
import io.legado.app.data.repository.DictionaryRuleRepository
import io.legado.app.data.repository.DictionaryRuleSnapshot
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DictionaryRuleEditScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savesAllEditedFieldsAndClosesAfterRepositorySuccess() {
        val repository = Fake()
        val model = DictionaryRuleEditViewModel(repository, SavedStateHandle(), null)
        var closes = 0
        compose.setContent {
            LegadoComposeTheme {
                DictionaryRuleEditRoute(model, {}, {}, { null }, {}, { closes++ })
            }
        }
        compose.onNodeWithTag("dictionary-rule-Name").performTextReplacement("new")
        compose
            .onNodeWithTag("dictionary-rule-UrlRule")
            .performScrollTo()
            .performTextReplacement("https://example.org/?q={{key}}")
        compose
            .onNodeWithTag("dictionary-rule-ShowRule")
            .performScrollTo()
            .performTextReplacement("@js:\nreturn 1")
        compose.onNodeWithTag("dictionary-rule-save").performClick()
        compose.waitUntil { closes == 1 }
        compose.runOnIdle {
            assertEquals(
                DictionaryRuleSnapshot("new", "https://example.org/?q={{key}}", "@js:\nreturn 1"),
                repository.saved.single(),
            )
        }
    }

    @Test
    fun backdropRequestsConfirmationAndKeepLeavesDraftIntact() {
        val model = DictionaryRuleEditViewModel(Fake(), SavedStateHandle(), null)
        var closes = 0
        compose.setContent {
            LegadoComposeTheme {
                DictionaryRuleEditRoute(model, {}, {}, { null }, {}, { closes++ })
            }
        }
        compose.onNodeWithTag("dictionary-rule-Name").performTextReplacement("draft")
        compose.onNodeWithTag("dictionary-rule-backdrop").performTouchInput {
            click(Offset(8f, 8f))
        }
        compose.onNodeWithTag("dictionary-rule-keep").performClick()
        compose.onNodeWithTag("dictionary-rule-Name").assertTextContains("draft")
        compose.runOnIdle {
            assertFalse(model.state.value.confirmExit)
            assertEquals(0, closes)
        }
    }

    @Test
    fun discardClosesWithoutSaving() {
        val repository = Fake()
        val model = DictionaryRuleEditViewModel(repository, SavedStateHandle(), null)
        var closes = 0
        compose.setContent {
            LegadoComposeTheme {
                DictionaryRuleEditRoute(model, {}, {}, { null }, {}, { closes++ })
            }
        }
        compose.onNodeWithTag("dictionary-rule-Name").performTextReplacement("draft")
        compose.onNodeWithTag("dictionary-rule-backdrop").performTouchInput {
            click(Offset(8f, 8f))
        }
        compose.onNodeWithTag("dictionary-rule-discard").performClick()
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertTrue(repository.saved.isEmpty()) }
    }

    @Test
    fun copyExportsUnsavedFieldsAsActualJson() {
        val model = DictionaryRuleEditViewModel(Fake(), SavedStateHandle(), null)
        var copied = ""
        compose.setContent {
            LegadoComposeTheme {
                DictionaryRuleEditRoute(model, {}, { copied = it }, { null }, {}, {})
            }
        }
        compose.onNodeWithTag("dictionary-rule-Name").performTextReplacement("draft")
        compose.onNodeWithTag("dictionary-rule-menu").performClick()
        compose.onNodeWithTag("dictionary-rule-copy").performClick()
        compose.runOnIdle {
            assertEquals("draft", JsonParser.parseString(copied).asJsonObject["name"].asString)
        }
    }

    @Test
    fun pasteImportsEditableFieldsThroughMenu() {
        val model = DictionaryRuleEditViewModel(Fake(), SavedStateHandle(), null)
        compose.setContent {
            LegadoComposeTheme {
                DictionaryRuleEditRoute(
                    model,
                    {},
                    {},
                    { "{\"name\":\"imported\",\"urlRule\":\"url\",\"showRule\":\"@js:return 2\"}" },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("dictionary-rule-menu").performClick()
        compose.onNodeWithTag("dictionary-rule-paste").performClick()
        compose.waitUntil { model.state.value.name == "imported" }
        compose.onNodeWithTag("dictionary-rule-Name").assertTextContains("imported")
        compose
            .onNodeWithTag("dictionary-rule-ShowRule")
            .performScrollTo()
            .assertTextContains("@js:return 2")
    }

    @Test
    fun fullscreenActionUsesFocusedFieldAndCursor() {
        val model = DictionaryRuleEditViewModel(Fake(), SavedStateHandle(), null)
        var request: DictionaryFullEditRequest? = null
        compose.setContent {
            LegadoComposeTheme {
                DictionaryRuleEditRoute(model, { request = it }, {}, { null }, {}, {})
            }
        }
        compose
            .onNodeWithTag("dictionary-rule-ShowRule")
            .performScrollTo()
            .performTextReplacement("@js:return 2")
        compose.onNodeWithTag("dictionary-rule-fullscreen").performClick()
        compose.runOnIdle {
            assertEquals(DictionaryRuleField.ShowRule, request?.field)
            assertEquals("@js:return 2", request?.text)
        }
    }

    @Test
    fun restoredFinishedClosesWithoutSavingAgain() {
        val repository = Fake()
        val model =
            DictionaryRuleEditViewModel(
                repository,
                SavedStateHandle(
                    mapOf("dictionary.editor.loaded" to true, "dictionary.editor.finished" to true)
                ),
                null,
            )
        var closes = 0
        compose.setContent {
            LegadoComposeTheme {
                DictionaryRuleEditRoute(model, {}, {}, { null }, {}, { closes++ })
            }
        }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertTrue(repository.saved.isEmpty()) }
    }

    private class Fake : DictionaryRuleRepository {
        val saved = mutableListOf<DictionaryRuleSnapshot>()

        override suspend fun load(name: String): DictionaryRuleSnapshot? = null

        override suspend fun save(previousName: String?, rule: DictionaryRuleSnapshot) {
            saved += rule
        }
    }
}
