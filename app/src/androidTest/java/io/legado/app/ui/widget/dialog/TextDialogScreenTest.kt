package io.legado.app.ui.widget.dialog

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextDialogScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: TextDialogViewModel

    private class Fake(val request: TextDialogRequest) : TextDialogRequestRepository {
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun load(id: String): TextDialogRequest {
            gate?.await()
            return request
        }
    }

    private val noImages =
        object : MarkdownImageRepository {
            override suspend fun load(source: String, width: Int) = null
        }

    private fun show(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        now: () -> Long = System::currentTimeMillis,
        edit: (TextDialogRequest) -> Unit = {},
        close: () -> Unit = {},
    ) {
        compose.runOnIdle { model = TextDialogViewModel(repo, saved, "request", now) }
        compose.setContent {
            LegadoComposeTheme {
                TextDialogRoute(model, noImages, { true }, {}, {}, edit, close, {})
            }
        }
    }

    private fun loaded() {
        compose.waitUntil(5000) { !model.state.value.loading && !model.state.value.searching }
        // Rendering can finish before the navigation drawer's close animation.
        compose.waitForIdle()
    }

    private fun bodyText(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("text-body")))

    private fun menu(tag: String) {
        compose.onNodeWithTag("text-menu").performClick()
        compose.onNodeWithTag("text-menu-$tag").performClick()
    }

    @After
    fun cleanup() {
        if (::model.isInitialized) compose.runOnIdle { model.stop() }
    }

    @Test
    fun tocSelectsSectionAndSearchingReturnsToFullDocumentThenClearsWhenAnotherSectionIsSelected() {
        show(Fake(TextDialogRequest("Help", "## One\nAlpha\n## Two\nBeta", "MD", showToc = true)))
        loaded()
        menu("toc")
        compose.onNodeWithTag("text-toc-2").performClick()
        loaded()
        bodyText("Beta").assertIsDisplayed()
        bodyText("Alpha").assertDoesNotExist()
        menu("search")
        compose.onNodeWithTag("text-search-input").performTextReplacement("Alpha")
        loaded()
        compose.onNodeWithTag("text-search-count").assertTextEquals("1/1")
        bodyText("Alpha").assertIsDisplayed()
        menu("toc")
        compose.onNodeWithTag("text-toc-2").performClick()
        loaded()
        compose
            .onNodeWithTag("text-search-input")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
        bodyText("Beta").assertIsDisplayed()
    }

    @Test
    fun searchHighlightsActualRenderedTextAndNextScrollsToTheOffscreenMatch() {
        val content =
            "## One\nneedle one\n\n" +
                (1..60).joinToString("\n\n") { "Paragraph $it" } +
                "\n\n## Two\nneedle two"
        show(Fake(TextDialogRequest("Help", content, "MD", showToc = true)))
        loaded()
        menu("search")
        compose.onNodeWithTag("text-search-input").performTextReplacement("needle")
        loaded()
        compose.onNodeWithText("needle one").assertIsDisplayed()
        compose.onNodeWithTag("text-search-count").assertTextEquals("1/2")
        val active = model.state.value.matches.first().parts.first()
        val text =
            compose
                .onNodeWithTag("rich-text-${active.leaf}-0")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .single()
        assertTrue(
            text.spanStyles.any {
                it.start == active.start &&
                    it.end == active.end &&
                    kotlin.math.abs(it.item.background.alpha - .5f) < .01f
            }
        )
        compose.onNodeWithTag("text-search-next").performClick()
        compose.onNodeWithTag("text-search-count").assertTextEquals("2/2")
        compose.onNodeWithText("needle two").assertIsDisplayed()
        compose.onNodeWithTag("text-search-next").performClick()
        compose.onNodeWithText("needle one").assertIsDisplayed()
        compose.onNodeWithTag("text-search-prev").performClick()
        compose.onNodeWithText("needle two").assertIsDisplayed()
    }

    @Test
    fun pendingDataDoesNotConsumeRestoredScrollUntilTheActualContentCanScroll() {
        val saved = SavedStateHandle(mapOf("text.scrollY" to 450))
        val repo =
            Fake(TextDialogRequest("Long text", (1..100).joinToString("\n") { "Line $it" })).apply {
                gate = CompletableDeferred()
            }
        show(repo, saved)
        compose.onNodeWithTag("text-working").assertExists()
        compose.runOnIdle {
            assertEquals(450, saved.get<Int>("text.scrollY"))
            repo.gate!!.complete(Unit)
        }
        loaded()
        compose.waitUntil { model.state.value.scroll == null }
        compose.runOnIdle { assertEquals(450, saved.get<Int>("text.scrollY")) }
    }

    @Test
    fun editorUsesFullContentOnceWhileThePlainViewerRetainsItsLimit() {
        val content = "Long ".repeat(10000)
        val requests = mutableListOf<TextDialogRequest>()
        show(Fake(TextDialogRequest("Log", content)), edit = { requests += it })
        loaded()
        menu("edit")
        compose.runOnIdle {
            assertEquals(1, requests.size)
            assertEquals(content, requests.single().content)
            assertEquals("TEXT", requests.single().mode)
        }
        compose.runOnIdle {
            assertNull(model.consumeEdit())
            assertFalse(model.state.value.finished)
        }
    }

    @Test
    fun countdownBlocksSystemCancellationButCloseMenuWorksAndAutoCloseIsDeliveredOnce() {
        var now = 1000L
        var closes = 0
        show(
            Fake(TextDialogRequest("Timed", "Content", time = 5000, autoClose = true)),
            now = { now },
            close = { closes++ },
        )
        loaded()
        compose.onNodeWithTag("text-countdown").assertTextEquals("5")
        compose.runOnIdle {
            assertFalse(model.state.value.canCancel)
            now += 5000
            model.tick()
        }
        compose.waitUntil { closes > 0 }
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun htmlViewerDoesNotExposeHelpOnlySearchOrTocButRetainsAnExplicitClose() {
        var closes = 0
        show(
            Fake(TextDialogRequest("HTML", "<b>Strong</b>", "HTML", time = 5000, showToc = true)),
            close = { closes++ },
        )
        loaded()
        compose.onNodeWithText("Strong").assertIsDisplayed()
        compose.onNodeWithTag("text-menu").performClick()
        compose.onNodeWithTag("text-menu-search").assertDoesNotExist()
        compose.onNodeWithTag("text-menu-toc").assertDoesNotExist()
        compose.onNodeWithTag("text-menu-close").performClick()
        compose.waitUntil { closes > 0 }
    }
}
