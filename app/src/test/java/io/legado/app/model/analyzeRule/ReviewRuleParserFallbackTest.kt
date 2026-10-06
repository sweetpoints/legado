package io.legado.app.model.analyzeRule

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.ReviewRule
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRuleParserFallbackTest {

    @Test
    fun bookSourceJsonRulesUseTheSamePureParser() {
        val result =
            ReviewRuleParser.parseSummary(
                body = "{\"items\": []}",
                rule =
                    ReviewRule(summaryListRule = "$.items", summaryParagraphIndexRule = "$.index"),
                source = BookSource(bookSourceUrl = "https://fixture.invalid"),
                book = Book(),
                chapter = BookChapter(),
                baseUrl = "https://fixture.invalid",
                context = EmptyCoroutineContext,
            )
        assertEquals(emptyMap<Int, Int>(), result?.counts)
    }

    @Test
    fun `missing optional JSONPath fields stay empty without error logs`() {
        val source =
            RssSource(
                sourceUrl = "https://example.com",
                sourceName = "Shared parser fixture",
            )
        val book =
            Book(
                bookUrl = "https://example.com/book",
                origin = source.sourceUrl,
            )
        val chapter =
            BookChapter(
                url = "https://example.com/chapter/1",
                bookUrl = book.bookUrl,
            )

        AppLog.clear()
        try {
            val result =
                ReviewRuleParser.parseDetailPage(
                    body =
                        """
                        {
                          "items": [
                            {
                              "UserName": "Alice",
                              "Content": "Hello",
                              "replies": [{"UserName": "Bob", "Content": "Reply"}]
                            }
                          ]
                        }
                        """
                            .trimIndent(),
                    rule =
                        ReviewRule(
                            detailListRule = "$.items",
                            detailAvatarRule = "$.UserHeadIcon",
                            detailNameRule = "$.UserName",
                            detailBadgeRule = "$.TitleInfoList[*].TitleImage",
                            detailContentRule = "$.Content",
                            replyListRule = "$.replies",
                            replyAvatarRule = "$.UserHeadIcon",
                            replyNameRule = "$.UserName",
                            replyBadgeRule = "$.TitleInfoList[*].TitleImage",
                            replyContentRule = "$.Content",
                        ),
                    nextPageRule = null,
                    baseUrl = chapter.url,
                    source = source,
                    book = book,
                    chapter = chapter,
                    context = EmptyCoroutineContext,
                    paraIndex = "1",
                    paraData = "key",
                    page = "1",
                )

            assertEquals(listOf("Alice"), result.items.map { it.name })
            with(result.items.single()) {
                assertNull(avatar)
                assertTrue(badges.isEmpty())
                assertEquals(listOf("Bob"), replies.map { it.name })
                assertNull(replies.single().avatar)
                assertTrue(replies.single().badges.isEmpty())
            }
            assertTrue(AppLog.logs.isEmpty())
        } finally {
            AppLog.clear()
        }
    }

    @Test
    fun `summary falls back to list order and ignores unusable counts`() {
        val source =
            RssSource(
                sourceUrl = "https://example.com",
                sourceName = "Shared parser fixture",
            )
        val book =
            Book(
                bookUrl = "https://example.com/book",
                origin = source.sourceUrl,
            )
        val chapter =
            BookChapter(
                url = "https://example.com/chapter/1",
                bookUrl = book.bookUrl,
            )

        AppLog.clear()
        try {
            val result =
                ReviewRuleParser.parseSummary(
                    body =
                        """
                        {
                          "items": [
                            {"data": "first", "count": "2"},
                            {"count": 3},
                            {"index": 0, "count": 9},
                            {"count": 0}
                          ]
                        }
                        """
                            .trimIndent(),
                    rule =
                        ReviewRule(
                            summaryListRule = "$.items",
                            summaryParagraphIndexRule = "$.index",
                            summaryParagraphDataRule = "$.data",
                            summaryCountRule = "$.count",
                        ),
                    source = source,
                    book = book,
                    chapter = chapter,
                    baseUrl = chapter.url,
                    context = EmptyCoroutineContext,
                )

            assertEquals(mapOf(1 to 2, 2 to 3), result?.counts)
            assertEquals(mapOf(1 to "first", 2 to "2"), result?.keys)
            assertTrue(AppLog.logs.isEmpty())
        } finally {
            AppLog.clear()
        }
    }

    @Test
    fun `missing required count JSONPath is recorded once`() {
        val source =
            RssSource(
                sourceUrl = "https://example.com",
                sourceName = "Shared parser fixture",
            )
        val book =
            Book(
                bookUrl = "https://example.com/book",
                origin = source.sourceUrl,
            )
        val chapter =
            BookChapter(
                url = "https://example.com/chapter/1",
                bookUrl = book.bookUrl,
            )

        AppLog.clear()
        try {
            val result =
                ReviewRuleParser.parseSummary(
                    body = """{"items":[{},{}]}""",
                    rule =
                        ReviewRule(
                            summaryListRule = "$.items",
                            summaryParagraphIndexRule = "$.index",
                            summaryCountRule = "$.count",
                        ),
                    source = source,
                    book = book,
                    chapter = chapter,
                    baseUrl = chapter.url,
                    context = EmptyCoroutineContext,
                )

            assertTrue(result?.counts.isNullOrEmpty())
            assertEquals(1, AppLog.logs.count { it.second.contains("$.count") })
        } finally {
            AppLog.clear()
        }
    }

    @Test
    fun `missing required detail and reply content JSONPaths are recorded once each`() {
        val source =
            RssSource(
                sourceUrl = "https://example.com",
                sourceName = "Shared parser fixture",
            )
        val book =
            Book(
                bookUrl = "https://example.com/book",
                origin = source.sourceUrl,
            )
        val chapter =
            BookChapter(
                url = "https://example.com/chapter/1",
                bookUrl = book.bookUrl,
            )

        AppLog.clear()
        try {
            val result =
                ReviewRuleParser.parseDetailPage(
                    body =
                        """
                        {
                          "items": [
                            {"UserName":"Alice","replies":[{"UserName":"Bob"}]}
                          ]
                        }
                        """
                            .trimIndent(),
                    rule =
                        ReviewRule(
                            detailListRule = "$.items",
                            detailNameRule = "$.UserName",
                            detailContentRule = "$.DetailContent",
                            replyListRule = "$.replies",
                            replyNameRule = "$.UserName",
                            replyContentRule = "$.ReplyContent",
                        ),
                    nextPageRule = null,
                    baseUrl = chapter.url,
                    source = source,
                    book = book,
                    chapter = chapter,
                    context = EmptyCoroutineContext,
                    paraIndex = "1",
                    paraData = "key",
                    page = "1",
                )

            assertEquals("Alice", result.items.single().name)
            assertEquals("Bob", result.items.single().replies.single().name)
            assertEquals(1, AppLog.logs.count { it.second.contains("$.DetailContent") })
            assertEquals(1, AppLog.logs.count { it.second.contains("$.ReplyContent") })
        } finally {
            AppLog.clear()
        }
    }
}
