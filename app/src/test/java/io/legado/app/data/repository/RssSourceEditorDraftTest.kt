package io.legado.app.data.repository

import io.legado.app.data.entities.RssSource
import org.junit.Assert.*
import org.junit.Test

class RssSourceEditorDraftTest {
    @Test
    fun everyTextFieldRoundTripsWithoutTrimmingNonblankRulesAndAllOptionsSurvive() {
        val fields =
            RssSourceEditorField.entries.associateWith {
                RssSourceEditorText("  ${it.key}\nrule  ", 3, 8)
            }
        val original =
            RssSourceEditorDraft(fields, false, true, false, true, false, false, true, true, 2, 4)
        val restored = RssSourceEditorDraft.from(original.entity())
        fields.forEach { (key, text) -> assertEquals(key.key, text.text, restored[key].text) }
        assertTrue(original.sameContent(restored))
        assertEquals(33, restored.fields.size)
        assertFalse(restored.enabled)
        assertFalse(restored.cookieJar)
        assertTrue(restored.preload)
        assertFalse(restored.enableJs)
        assertFalse(restored.loadWithBaseUrl)
        assertTrue(restored.showWebLog)
        assertTrue(restored.cacheFirst)
        assertEquals(2, restored.type)
        assertEquals(4, restored.articleStyle)
    }

    @Test
    fun blankFieldsNormalizeToNullButUrlAndNameAreRequiredAndCursorOnlyIsNotDirty() {
        val base = RssSourceEditorDraft.from(RssSource("url", "name"))
        assertTrue(base.valid())
        assertFalse(base.with(RssSourceEditorField.SourceUrl, RssSourceEditorText("  ")).valid())
        val cursor = base.with(RssSourceEditorField.SourceName, RssSourceEditorText("name", 4, 9))
        assertTrue(base.sameContent(cursor))
        assertEquals(4, cursor[RssSourceEditorField.SourceName].end)
        val whitespace = base.with(RssSourceEditorField.Header, RssSourceEditorText("\n "))
        assertNull(whitespace.entity().header)
        assertTrue(base.sameContent(whitespace))
        assertFalse(
            base.sameContent(base.with(RssSourceEditorField.RuleImage, RssSourceEditorText("img")))
        )
        assertFalse(
            base.sameContent(
                base.with(RssSourceEditorField.ContentWhitelist, RssSourceEditorText("allowed"))
            )
        )
        assertFalse(
            base.sameContent(base.with(RssSourceEditorField.JsLib, RssSourceEditorText("library")))
        )
    }

    @Test
    fun nativeTypeBoundsAndRuleCompletionPreserveTextLinkImageAndNextContentSemantics() {
        val draft =
            RssSourceEditorDraft.from(
                RssSource(
                    "url",
                    "name",
                    ruleArticles = ".entry",
                    ruleTitle = ".title",
                    ruleImage = "img",
                    ruleLink = "a",
                    ruleNextPage = ".next",
                    ruleContent = ".body",
                    nextContentUrl = ".more",
                    type = 99,
                    articleStyle = -1,
                )
            )
        assertEquals(0, draft.type)
        assertEquals(0, draft.articleStyle)
        val completed = draft.entity(autoComplete = true)
        assertEquals(".title@text", completed.ruleTitle)
        assertEquals("img@src", completed.ruleImage)
        assertEquals(
            "a@text",
            completed.ruleLink,
        ) // Preserve the native RSS editor default completion type.
        assertEquals(".next@href", completed.ruleNextPage)
        assertEquals(".body@text", completed.ruleContent)
        assertEquals(".more@href", completed.nextContentUrl)
        assertEquals(".more", draft.entity().nextContentUrl)
        val complex =
            draft.with(RssSourceEditorField.RuleArticles, RssSourceEditorText("@js:result"))
        assertEquals(".title", complex.entity(autoComplete = true).ruleTitle)
        assertEquals(".more@href", complex.entity(autoComplete = true).nextContentUrl)
    }

    @Test
    fun editingPreservesFreshMetadataAndDocumentsContainNoMutableEntity() {
        val base = RssSource("url", "name", lastUpdateTime = 88, customOrder = 17)
        val draft =
            RssSourceEditorDraft.from(base)
                .with(RssSourceEditorField.SourceName, RssSourceEditorText("changed"))
        val updated = draft.entity(base)
        assertEquals(88L, updated.lastUpdateTime)
        assertEquals(17, updated.customOrder)
        assertEquals("name", base.sourceName)
        assertEquals("changed", updated.sourceName)
    }
}
