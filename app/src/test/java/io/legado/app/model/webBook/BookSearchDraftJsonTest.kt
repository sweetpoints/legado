package io.legado.app.model.webBook

import com.google.gson.JsonParser
import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSearchDraftJsonTest {
    @Test
    fun privateDraftUsesStableFieldAndEffectNamesAndRestoresCompletePayload() {
        val book =
            SearchBook(bookUrl = "synthetic-url", name = "Title", author = "Author").apply {
                infoHtml = "synthetic-html"
                addOrigin("alternate-source")
            }
        val result = BookSearchResult.from(book)
        val draft =
            BookSearchDraft(
                revision = 12,
                query = "query",
                results = listOf(result),
                effects =
                    listOf(
                        BookSearchReceipt(
                            "receipt",
                            BookSearchEffect.BookInfo,
                            resultId = result.id,
                        )
                    ),
            )
        val json = GSON.toJson(draft)
        val fields = JsonParser.parseString(json).asJsonObject
        assertEquals(12L, fields["revision"].asLong)
        assertEquals("query", fields["query"].asString)
        assertEquals(
            "synthetic-html",
            fields["results"].asJsonArray[0].asJsonObject["infoHtml"].asString,
        )
        assertEquals("BookInfo", fields["effects"].asJsonArray[0].asJsonObject["effect"].asString)
        assertEquals(draft, GSON.fromJson(json, BookSearchDraft::class.java))
    }
}
