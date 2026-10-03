package io.legado.app.ui.highlight.edit

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightRule
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HighlightRuleEditDialogComposeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun realExistingRuleRestoresDraftAndStyleHostThenSavesAllMetadata() {
        val original =
            HighlightRule(
                    name = "Editor ${UUID.randomUUID()}",
                    pattern = "literal",
                    scope = "Book",
                    isEnabled = false,
                    order = 777,
                    timeoutMillisecond = 4567,
                    group = "Original",
                    applyToBody = false,
                    applyToTitle = true,
                )
                .apply { applyStyle(HighlightStyle(fill = 123, fontPath = "font", fontSize = 25f)) }
        val id = runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.insert(original).single() }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity {
                    HighlightRuleEditDialog.edit(id).show(it.supportFragmentManager, "editor")
                }
                waitReady()
                compose.onNodeWithTag("highlight-rule-name").performTextReplacement("Edited")
                compose
                    .onNodeWithTag("highlight-rule-group")
                    .performScrollTo()
                    .performTextReplacement(" New group ")
                scenario.onActivity {
                    val editor =
                        it.supportFragmentManager.findFragmentByTag("editor")
                            as HighlightRuleEditDialog
                    assertEquals(25f, editor.currentHighlightStyle().fontSize)
                    editor.onHighlightStyleChanged(editor.currentHighlightStyle().copy(bold = true))
                }
                compose
                    .onNodeWithTag("highlight-rule-name")
                    .performScrollTo()
                    .performSemanticsAction(
                        androidx.compose.ui.semantics.SemanticsActions.SetSelection
                    ) {
                        it(1, 4, false)
                    }
                scenario.recreate()
                waitReady()
                compose.onNodeWithTag("highlight-rule-name").assertTextEquals("Edited")
                compose
                    .onNodeWithTag("highlight-rule-name")
                    .assert(
                        SemanticsMatcher.expectValue(
                            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange,
                            androidx.compose.ui.text.TextRange(1, 4),
                        )
                    )
                compose
                    .onNodeWithTag("highlight-rule-group")
                    .performScrollTo()
                    .assertTextEquals(" New group ")
                scenario.onActivity {
                    val editor =
                        it.supportFragmentManager.findFragmentByTag("editor")
                            as HighlightRuleEditDialog
                    assertTrue(editor.currentHighlightStyle().bold)
                }
                compose.onNodeWithTag("highlight-rule-save").performClick()
                compose.waitUntil {
                    runBlocking(Dispatchers.IO) {
                        appDb.highlightRuleDao.findById(id)?.name == "Edited"
                    }
                }
                val saved = runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.findById(id) }!!
                assertEquals(original.uuid, saved.uuid)
                assertFalse(saved.isEnabled)
                assertEquals(777, saved.order)
                assertEquals(4567L, saved.timeoutMillisecond)
                assertFalse(saved.applyToBody)
                assertTrue(saved.applyToTitle)
                assertEquals("New group", saved.group)
                assertTrue(saved.styleObj().bold)
                assertEquals("font", saved.styleObj().fontPath)
                assertEquals(25f, saved.styleObj().fontSize)
                scenario.onActivity {
                    it.supportFragmentManager.executePendingTransactions()
                    assertNull(it.supportFragmentManager.findFragmentByTag("editor"))
                }
            }
        } finally {
            runBlocking(Dispatchers.IO) {
                appDb.highlightRuleDao.findById(id)?.let { appDb.highlightRuleDao.delete(it) }
            }
        }
    }

    @Test
    fun realNewRuleUsesSmallArgumentsAndColorPickerWithCancelOnlyDraftAndSourceManualHighlightRemains() {
        val pattern = "Rule ${UUID.randomUUID()}"
        val manual =
            BookHighlight(
                time = System.nanoTime(),
                bookText = pattern,
                bookName = "Book",
                bookUrl = "https://manual.invalid",
            )
        runBlocking(Dispatchers.IO) { appDb.bookHighlightDao.insert(manual) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val editor =
                        HighlightRuleEditDialog.create(
                            pattern,
                            scope = "Book",
                            style = GSON.toJson(HighlightStyle(fill = 321)),
                        )
                    assertFalse(editor.requireArguments().containsKey("pattern"))
                    assertTrue(editor.requireArguments().getString("seed")!!.length < 100)
                    editor.show(it.supportFragmentManager, "editor")
                }
                waitReady()
                scenario.onActivity {
                    (it.supportFragmentManager.findFragmentByTag("editor")
                            as HighlightRuleEditDialog)
                        .pickHighlightColor(HighlightStyleDialog.HL_FILL, 321, true)
                }
                compose
                    .onNodeWithTag("highlight-rule-color-hex")
                    .performScrollTo()
                    .performTextReplacement("#80123456")
                scenario.recreate()
                waitReady()
                compose
                    .onNodeWithTag("highlight-rule-color-hex")
                    .performScrollTo()
                    .assertTextEquals("#80123456")
                compose.onNodeWithTag("highlight-rule-color-save").performClick()
                scenario.onActivity {
                    assertEquals(
                        0x80123456.toInt(),
                        (it.supportFragmentManager.findFragmentByTag("editor")
                                as HighlightRuleEditDialog)
                            .currentHighlightStyle()
                            .fill,
                    )
                }
                assertTrue(
                    runBlocking(Dispatchers.IO) {
                        appDb.highlightRuleDao.all.none { it.pattern == pattern }
                    }
                )
                compose.onNodeWithTag("highlight-rule-save").performClick()
                compose.waitUntil {
                    runBlocking(Dispatchers.IO) {
                        appDb.highlightRuleDao.all.any { it.pattern == pattern }
                    }
                }
                assertEquals(
                    GSON.toJson(manual),
                    GSON.toJson(
                        runBlocking(Dispatchers.IO) {
                            appDb.bookHighlightDao.all.first { it.time == manual.time }
                        }
                    ),
                )
            }
        } finally {
            runBlocking(Dispatchers.IO) {
                appDb.highlightRuleDao.all
                    .filter { it.pattern == pattern }
                    .forEach { appDb.highlightRuleDao.delete(it) }
                appDb.bookHighlightDao.delete(manual)
            }
        }
    }

    private fun waitReady() = compose.waitUntil {
        compose.onAllNodesWithTag("highlight-rule-save").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
    }
}
