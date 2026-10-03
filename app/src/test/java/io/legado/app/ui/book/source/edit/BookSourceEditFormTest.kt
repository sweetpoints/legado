package io.legado.app.ui.book.source.edit

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceEditFormTest {
    @Test
    fun projectionContainsAllLegacyFieldsWithoutMutatingMissingRules() {
        val source = BookSource()
        val form = projectBookSourceEditForm(source)
        assertEquals(listOf(13, 11, 10, 11, 10, 13, 20), form.tabs.map { it.size })
        form.tabs.forEach { fields ->
            assertEquals(fields.size, fields.map { it.key }.distinct().size)
        }
        assertNull(source.ruleSearch)
        assertNull(source.ruleExplore)
        assertNull(source.ruleBookInfo)
        assertNull(source.ruleToc)
        assertNull(source.ruleContent)
        assertNull(source.ruleReview)
    }

    @Test
    fun editingSameNamedFieldsDoesNotChangeOtherTabOrOriginalProjection() {
        val source =
            BookSource(
                ruleSearch = SearchRule(name = "search"),
                ruleBookInfo = BookInfoRule(name = "info"),
            )
        val original = projectBookSourceEditForm(source)
        val edited = original.updateField(3, "name", "updated")
        assertEquals("info", original.field(3, "name")?.value)
        assertEquals("updated", edited.field(3, "name")?.value)
        assertEquals("search", edited.field(1, "name")?.value)
        assertEquals("info", source.ruleBookInfo?.name)
    }

    @Test
    fun materializationPreservesMetadataAndDoesNotAliasOriginalRuleObjects() {
        val source =
            BookSource(
                bookSourceUrl = "https://source",
                bookSourceName = "Source",
                weight = 42,
                customOrder = 15,
                respondTime = 18,
                exploreScreen = "screen",
                ruleSearch = SearchRule(name = "title", author = "author"),
                ruleReview = ReviewRule(enabled = false, detailContentRule = "content"),
            )
        val form = projectBookSourceEditForm(source).updateField(1, "name", "updated")
        val result = materializeBookSourceEditForm(source, form)
        assertEquals(42, result.weight)
        assertEquals(15, result.customOrder)
        assertEquals(18L, result.respondTime)
        assertEquals("screen", result.exploreScreen)
        assertEquals("updated", result.ruleSearch?.name)
        assertEquals("title", source.ruleSearch?.name)
        assertNotSame(source.ruleSearch, result.ruleSearch)
        assertNotSame(source.ruleReview, result.ruleReview)
        assertEquals("content", result.ruleReview?.detailContentRule)
    }

    @Test
    fun completionRetainsListContextAndTextLinkImageTypes() {
        val source =
            BookSource(
                ruleSearch =
                    SearchRule(bookList = "div.list", name = "h3", bookUrl = "a", coverUrl = "img"),
                ruleContent = ContentRule(content = "p", nextContentUrl = "a.next"),
            )
        val form = projectBookSourceEditForm(source)
        val result = materializeBookSourceEditForm(source, form, autoComplete = true)
        assertEquals("h3@text", result.ruleSearch?.name)
        assertEquals("a@href", result.ruleSearch?.bookUrl)
        assertEquals("img@src", result.ruleSearch?.coverUrl)
        assertEquals("p@text", result.ruleContent?.content)
        assertEquals("a.next@href", result.ruleContent?.nextContentUrl)
        val jsListSource = source.copy(ruleSearch = SearchRule(bookList = "@js:list", name = "h3"))
        val jsResult =
            materializeBookSourceEditForm(
                jsListSource,
                projectBookSourceEditForm(jsListSource),
                true,
            )
        assertEquals("h3", jsResult.ruleSearch?.name)
    }

    @Test
    fun blankNormalizationNumericParsingAndOptionalReviewFollowLegacyRules() {
        val source = BookSource()
        val original = projectBookSourceEditForm(source)
        val invalid =
            original.updateField(0, "header", "   ").updateField(5, "maxBatchSize", "invalid")
        val invalidResult = materializeBookSourceEditForm(source, invalid)
        assertNull(invalidResult.header)
        assertNull(invalidResult.ruleContent?.maxBatchSize)
        assertNull(invalidResult.ruleReview)
        val edited =
            original
                .updateField(5, "maxBatchSize", "22")
                .updateField(6, "detailContentRule", "body")
        val result = materializeBookSourceEditForm(source, edited)
        assertEquals(22, result.ruleContent?.maxBatchSize)
        assertEquals("body", result.ruleReview?.detailContentRule)
        assertFalse(result.ruleReview!!.enabled)
        val enabled = original.copy(options = original.options.copy(enabledReview = true))
        assertTrue(materializeBookSourceEditForm(source, enabled).ruleReview!!.enabled)
    }

    @Test
    fun keyboardInsertionUsesRawUtf16OffsetsAndReversedSelection() {
        val original = projectBookSourceEditForm(BookSource(header = "A\r\n中😀Z"))
        val selected = original.updateField(0, "header", "A\r\n中😀Z", 6, 3)
        val inserted = selected.insert(0, "header", "x")
        assertEquals("A\r\nxZ", inserted.field(0, "header")!!.value)
        assertEquals(4, inserted.field(0, "header")!!.selectionStart)
        assertEquals(4, inserted.field(0, "header")!!.selectionEnd)
        assertEquals("A\r\n中😀Z", original.field(0, "header")!!.value)
        assertEquals(6, selected.field(0, "header")!!.selectionStart)
        assertEquals(3, selected.field(0, "header")!!.selectionEnd)
    }

    @Test
    fun changingFieldClampsSelectionAndDoesNotMoveAnotherTabCursor() {
        val original =
            projectBookSourceEditForm(BookSource())
                .updateField(1, "name", "search title", 4, 8)
                .updateField(3, "name", "detail title", 5, 7)
        val updated = original.updateField(1, "name", "x")
        assertEquals(1, updated.field(1, "name")!!.selectionStart)
        assertEquals(1, updated.field(1, "name")!!.selectionEnd)
        assertEquals(5, updated.field(3, "name")!!.selectionStart)
        assertEquals(7, updated.field(3, "name")!!.selectionEnd)
    }

    @Test
    fun invalidSelectionIsBoundedBeforeKeyboardInsertion() {
        val form = projectBookSourceEditForm(BookSource()).updateField(0, "jsLib", "abc", -5, 100)
        assertEquals(0, form.field(0, "jsLib")!!.selectionStart)
        assertEquals(3, form.field(0, "jsLib")!!.selectionEnd)
        assertEquals("replace", form.insert(0, "jsLib", "replace").field(0, "jsLib")!!.value)
        assertEquals(form, form.insert(0, "jsLib", ""))
        assertEquals(form, form.insert(9, "missing", "text"))
    }
}
