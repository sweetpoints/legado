package io.legado.app.model.jsSource

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceReviewTest {

    private val book =
        Book(
            bookUrl = "https://example.com/book/1",
            name = "测试书",
        )
    private val chapter =
        BookChapter(
            bookUrl = book.bookUrl,
            title = "第1章",
            url = "https://example.com/chapter/1",
        )

    @Test
    fun `detail flattens deep replies without recursive parsing`() {
        var reply = JsonObject().apply { addProperty("content", "leaf") }
        repeat(2_048) { index ->
            reply =
                JsonObject().apply {
                    addProperty("id", "r$index")
                    addProperty("content", "reply")
                    add("replies", JsonArray().apply { add(reply) })
                }
        }
        val result =
            JsonObject().apply {
                add(
                    "items",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("content", "main")
                                add("replies", JsonArray().apply { add(reply) })
                            }
                        )
                    },
                )
            }

        val replies = JsSourceReview.parseDetailObject(result, chapter.url)!!.items.single().replies

        assertEquals(2_049, replies.size)
        assertTrue(replies.all { it.replies.isEmpty() })
    }

    @Test
    fun `remembered missing capability skips script execution`() = runBlocking {
        val source = source("throw 'script should not execute';")
        JsSourceReview.rememberReviewCapability(source, enabled = false)

        val result = JsSourceReview.getReviewSummaryAwait(source, book, chapter)

        assertNull(result)
        assertEquals(false, JsSourceReview.hasReviewCapability(source))
    }

    private fun source(reviewFunctions: String): BookSource {
        return BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "段评测试",
            mainJs =
                """
                var config = {
                    bookSourceUrl: "https://example.com",
                    bookSourceName: "段评测试"
                };
                function search() { return []; }
                function getChapters() { return []; }
                function getContent() { return ""; }
                $reviewFunctions
            """
                    .trimIndent(),
        )
    }
}
