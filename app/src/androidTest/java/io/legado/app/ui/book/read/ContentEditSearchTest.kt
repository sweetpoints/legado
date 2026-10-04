package io.legado.app.ui.book.read

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.sendToClip
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Drives real Compose text editing, selection, slider and restored route behavior. */
class ContentEditSearchTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<ContentEditorViewModel>()
    private val target = ContentEditorTarget("fixed-ui-book", 2, 0)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun close() {
        compose.runOnIdle { models.forEach { it.viewModelScope.cancel() } }
    }

    private fun create(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        ContentEditorViewModel(repo, saved, target, "Editor title").also { models += it }

    private fun show(
        repo: Fake = Fake("initial"),
        saved: SavedStateHandle = SavedStateHandle(),
        reload: () -> Unit = {},
        close: () -> Unit = {},
    ): ContentEditorViewModel {
        val model = create(repo, saved)
        compose.setContent {
            LegadoComposeTheme {
                ContentEditorRoute(model, { context.sendToClip(it) }, reload, close)
            }
        }
        compose.waitUntil { model.state.value.hasDraft || model.state.value.finished }
        return model
    }

    private fun menu(tag: String) {
        compose.onNodeWithTag("content-menu").performClick()
        compose.onNodeWithTag(tag).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
    }

    private fun search(query: String) {
        compose.onNodeWithTag("content-search").performClick()
        compose.onNodeWithTag("content-query").performTextReplacement(query)
    }

    private fun snapshot(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun plainTextEditsCopyAndColdRestorationKeepImageCodeInTheSavedDraft() {
        val repo = Fake("甲<img src=\"图\">乙丙")
        val saved = SavedStateHandle()
        var model by mutableStateOf(create(repo, saved))
        var copied: String? = null
        compose.setContent {
            LegadoComposeTheme {
                ContentEditorRoute(model, { copied = it; context.sendToClip(it) }, {}, {})
            }
        }
        compose.waitUntil { model.state.value.hasDraft }
        menu("content-plain")
        compose.onNodeWithTag("content-body").assertTextEquals("甲乙丙").performTextReplacement("甲替换丙")
        compose.runOnIdle { assertEquals("甲<img src=\"图\">替换丙", model.state.value.raw) }
        menu("content-copy")
        compose.runOnIdle { assertEquals("Editor title\n甲替换丙", copied) }
        compose.waitUntil {
            copied == "Editor title\n甲替换丙" &&
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip?.getItemAt(0)?.text?.toString() == copied
        }
        compose.waitUntil { repo.drafts[model.draftId]?.text == model.state.value.raw }
        compose.runOnIdle { model = create(repo, snapshot(saved)) }
        compose.waitUntil { model.state.value.hasDraft }
        compose.onNodeWithTag("content-body").assertTextEquals("甲替换丙")
        compose.onNodeWithTag("content-save").performClick()
        compose.waitUntil { repo.saves.isNotEmpty() }
        assertEquals("甲<img src=\"图\">替换丙", repo.saves.single())
    }

    @Test
    fun hiddenImageCaretStaysCollapsedWhenSwitchingAndRestoringRawMode() {
        val repo = Fake("甲<img src=\"图\">乙")
        val model = show(repo)
        menu("content-plain")
        compose.onNodeWithTag("content-body").performTextInputSelection(TextRange(1))
        menu("content-plain")
        compose.runOnIdle {
            assertEquals(model.state.value.raw.indexOf('乙'), model.state.value.selectionStart)
            assertEquals(model.state.value.selectionStart, model.state.value.selectionEnd)
        }
        menu("content-plain")
        compose.runOnIdle {
            assertEquals(1, model.state.value.selectionStart)
            assertFalse(model.state.value.hasChanges)
        }
    }

    @Test
    fun resettingContentInvalidatesCompletedAndPendingSearches() {
        val repo = Fake("needle needle")
        val model = show(repo)
        search("needle")
        compose.waitUntil { model.state.value.matches.size == 2 }
        menu("content-reset")
        compose.waitUntil {
            !model.state.value.loading &&
                model.state.value.text == "" &&
                model.state.value.matches.isEmpty()
        }
        compose.onNodeWithTag("content-count").assertTextEquals("0/0")
        compose.onNodeWithTag("content-next").assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(model.state.value.hasChanges)
            assertEquals(1, repo.resets)
        }
    }

    @Test
    fun literalRegexCaseAndCircularNavigationDoNotEditTheDraft() {
        val repo = Fake("One one ONE")
        val model = show(repo)
        search("one")
        compose.waitUntil { model.state.value.matches.size == 3 }
        compose.onNodeWithTag("content-prev").performClick()
        compose.onNodeWithTag("content-count").assertTextEquals("3/3")
        compose.onNodeWithTag("content-case").performClick()
        compose.waitUntil { model.state.value.matches.size == 1 }
        compose.onNodeWithTag("content-regex").performClick()
        compose.onNodeWithTag("content-query").performTextReplacement("[")
        compose.waitUntil { model.state.value.searchInvalid }
        compose.onNodeWithTag("content-query-error").assertExists()
        compose.onNodeWithTag("content-next").assertIsNotEnabled()
        compose.onNodeWithTag("content-query").performTextReplacement("(?=one)")
        compose.waitUntil {
            model.state.value.matches.size == 1 && !model.state.value.searchInvalid
        }
        compose.runOnIdle {
            assertEquals(model.state.value.selectionStart, model.state.value.selectionEnd)
            assertFalse(model.state.value.hasChanges)
            assertTrue(repo.saves.isEmpty())
        }
    }

    @Test
    fun editsRefreshMatchesWithoutMovingSelectionAndSaveTheActualDraft() {
        val repo = Fake("one one")
        val model = show(repo)
        search("one")
        compose.waitUntil { model.state.value.matches.size == 2 }
        compose.onNodeWithTag("content-body").performTextReplacement("one one!")
        compose.waitUntil {
            model.state.value.text == "one one!" && model.state.value.matches.size == 2
        }
        compose.runOnIdle {
            assertEquals(8, model.state.value.selectionStart)
            assertEquals(8, model.state.value.selectionEnd)
        }
        compose.onNodeWithTag("content-save").performClick()
        compose.waitUntil { repo.saves.isNotEmpty() }
        assertEquals(listOf("one one!"), repo.saves)
    }

    @Test
    fun positionBarDragsTextWithoutMovingCaretAndUpdatesAfterEdits() {
        val repo = Fake((0..499).joinToString("\n") { "Line $it" })
        val model = show(repo)
        compose.onNodeWithTag("content-body").performTextInputSelection(TextRange(3))
        compose.onNodeWithTag("content-position").performTouchInput {
            swipe(topCenter, bottomCenter, 700)
        }
        compose.waitUntil { (model.state.value.scrollY ?: 0) > 500 }
        compose.runOnIdle {
            assertEquals(3, model.state.value.selectionStart)
            assertEquals(3, model.state.value.selectionEnd)
        }
        compose.onNodeWithTag("content-body").performTextReplacement("short")
        compose.waitForIdle()
        compose.onNodeWithTag("content-position").assertIsNotEnabled()
    }

    @Test
    fun restorationRetainsSearchSelectionAndPositionWithoutEmbeddingBodyInSavedState() {
        val repo = Fake((0..200).joinToString("\n") { "Line $it needle" })
        val saved = SavedStateHandle()
        var model by mutableStateOf(create(repo, saved))
        compose.setContent { LegadoComposeTheme { ContentEditorRoute(model, {}, {}, {}) } }
        compose.waitUntil { model.state.value.hasDraft }
        search("needle")
        compose.waitUntil { model.state.value.matches.size == 201 }
        compose.onNodeWithTag("content-position").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(0.7f)
        }
        compose.waitUntil { (model.state.value.scrollY ?: 0) > 500 }
        var y = 0
        compose.runOnIdle {
            y = model.state.value.scrollY!!
            model = create(repo, snapshot(saved))
        }
        compose.waitUntil { model.state.value.hasDraft && model.state.value.matches.size == 201 }
        compose.onNodeWithTag("content-query").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("needle"))
        )
        compose.runOnIdle {
            assertEquals(y, model.state.value.scrollY)
            assertFalse(model.state.value.hasChanges)
        }
    }

    @Test
    fun failedAutomaticSaveKeepsVisibleDraftAndAllowsRetry() {
        val repo = Fake("initial").apply { saveFailure = true }
        var closes = 0
        val model = show(repo, close = { closes++ })
        compose.onNodeWithTag("content-body").performTextReplacement("changed")
        compose.onNodeWithTag("content-close").performClick()
        compose.waitUntil { model.state.value.error != null }
        compose.onNodeWithTag("content-error").assertTextEquals("disk full")
        compose.onNodeWithTag("content-body").assertTextEquals("changed")
        assertEquals(0, closes)
        compose.runOnIdle { repo.saveFailure = false }
        compose.onNodeWithTag("content-close").performClick()
        compose.waitUntil { closes == 1 }
        assertEquals(listOf("changed"), repo.saves)
    }

    @Test
    fun titleEditingPersistsImmediatelyWithoutSavingBodyAndTriggersOneReload() {
        val repo = Fake("initial")
        var reloads = 0
        val model = show(repo, reload = { reloads++ })
        compose.onNodeWithTag("content-title").performClick()
        compose.waitUntil { model.state.value.titleEditor && !model.state.value.saving }
        compose.onNodeWithTag("content-title-input").performTextReplacement("changed title")
        compose.onNodeWithTag("content-title-save").performClick()
        compose.waitUntil { reloads == 1 }
        compose.onNodeWithTag("content-title").assertTextEquals("changed title")
        assertEquals("changed title", repo.title)
        assertTrue(repo.saves.isEmpty())
    }

    @Test
    fun finishedRestorationClosesWithoutRepeatingConsumedReaderReloadOrSave() {
        val repo = Fake("body")
        var reloads = 0
        var closes = 0
        show(
            repo,
            SavedStateHandle(
                mapOf("contentEditor.finished" to true, "contentEditor.reload" to false)
            ),
            { reloads++ },
            { closes++ },
        )
        compose.waitUntil { closes == 1 }
        compose.runOnIdle {
            assertEquals(0, reloads)
            assertTrue(repo.saves.isEmpty())
        }
    }

    @Test
    fun readerReloadAndCloseWaitForResumedAndDoNotRepeat() {
        val owner = Owner()
        val repo = Fake("body")
        var reloads = 0
        var closes = 0
        lateinit var model: ContentEditorViewModel
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            model = create(repo)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme { ContentEditorRoute(model, {}, { reloads++ }, { closes++ }) }
            }
        }
        compose.waitUntil { model.state.value.hasDraft }
        compose.onNodeWithTag("content-save").performClick()
        compose.waitUntil { model.state.value.finished }
        compose.runOnIdle {
            assertEquals(0, reloads)
            assertEquals(0, closes)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertEquals(1, reloads)
            assertEquals(1, closes)
            assertEquals(listOf("body"), repo.saves)
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake(var body: String) : ContentEditorRepository {
        var plain = false
        var title = "DB title"
        var saveFailure = false
        var resets = 0
        val drafts = mutableMapOf<String, ContentEditorDraft>()
        val saves = mutableListOf<String>()

        override suspend fun load(
            target: ContentEditorTarget,
            reset: Boolean,
        ): ContentEditorLoaded {
            if (reset) {
                resets++
                return ContentEditorLoaded("", title)
            }
            return ContentEditorLoaded(body, title)
        }

        override suspend fun save(target: ContentEditorTarget, text: String) {
            if (saveFailure) error("disk full")
            saves += text
        }

        override suspend fun title(target: ContentEditorTarget) = title

        override suspend fun saveTitle(target: ContentEditorTarget, title: String): String {
            this.title = title
            return title
        }

        override suspend fun plainText() = plain

        override suspend fun setPlainText(value: Boolean) {
            plain = value
        }

        override suspend fun readDraft(id: String) = drafts[id]

        override suspend fun writeDraft(id: String, draft: ContentEditorDraft) {
            if ((drafts[id]?.revision ?: -1) <= draft.revision) drafts[id] = draft
        }

        override suspend fun deleteDraft(id: String) {
            drafts.remove(id)
        }
    }
}
