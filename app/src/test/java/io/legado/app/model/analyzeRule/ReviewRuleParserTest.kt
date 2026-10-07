package io.legado.app.model.analyzeRule

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.ReviewRule
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRuleParserTest {

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

    private val source =
        RssSource(
            sourceUrl = "https://example.com",
            sourceName = "Shared parser fixture",
        )
    private val book = Book(bookUrl = "https://example.com/book", origin = source.sourceUrl)
    private val chapter =
        BookChapter(
            url = "https://example.com/chapter/1",
            bookUrl = book.bookUrl,
        )

    @Test
    fun `summary configuration requires every lookup rule`() {
        val rule =
            ReviewRule(
                enabled = true,
                reviewSummaryUrl = "https://example.com/reviews",
                summaryListRule = "$.items",
                summaryParagraphIndexRule = "$.index",
                summaryCountRule = "$.count",
            )

        assertEquals("https://example.com/reviews", rule.configuredSummaryUrl())
        rule.summaryCountRule = null
        assertEquals(null, rule.configuredSummaryUrl())
    }

    @Test
    fun `declarative detail preserves 64-bit numeric ids`() {
        val result =
            ReviewRuleParser.parseDetailPage(
                body = """{"items":[{"id":1051979893439332353,"content":"评论"}]}""",
                rule =
                    ReviewRule(
                        detailListRule = "$.items",
                        detailIdRule = "$.id",
                        detailContentRule = "$.content",
                    ),
                nextPageRule = null,
                baseUrl = chapter.url,
                source = source,
                book = book,
                chapter = chapter,
                context = EmptyCoroutineContext,
                paraIndex = "1",
                paraData = "",
                page = "1",
            )

        assertEquals("1051979893439332353", result.items.single().id)
    }

    @Test
    fun `parses a standalone reply page with reply rules`() {
        val replies =
            ReviewRuleParser.parseReplyPage(
                body =
                    """
                    {
                      "data": {
                        "reply_list": [
                          {
                            "id": "r1",
                            "avatar": "/reply.png",
                            "name": "Bob",
                            "badges": ["reader", "top"],
                            "content": "{\"text\":\"Reply\",\"replyToName\":\"Alice\",\"img\":\"/reply.jpg\",\"time\":\"now\",\"likeCount\":4}"
                          }
                        ]
                      }
                    }
                    """
                        .trimIndent(),
                rule =
                    ReviewRule(
                        replyListRule = "$.data.reply_list",
                        replyIdRule = "$.id",
                        replyAvatarRule = "$.avatar",
                        replyNameRule = "$.name",
                        replyBadgeRule = "$.badges",
                        replyContentRule = "$.content",
                    ),
                baseUrl = chapter.url,
                source = source,
                book = book,
                chapter = chapter,
                context = EmptyCoroutineContext,
                paraIndex = "8",
                paraData = "7",
                page = "2",
            )

        with(replies.single()) {
            assertEquals("r1", id)
            assertEquals("https://example.com/reply.png", avatar)
            assertEquals("Bob", name)
            assertEquals("Alice", replyToName)
            assertEquals(listOf("reader", "top"), badges)
            assertEquals("Reply", content)
            assertEquals("https://example.com/reply.jpg", imageUrl)
            assertEquals("now", time)
            assertEquals(4, likeCount)
            assertTrue(this.replies.isEmpty())
        }
    }

    @Test
    fun `legacy review rules survive converter round trip and equality checks them`() {
        val converters = BookSource.Converters()
        val legacy =
            requireNotNull(
                converters.stringToReviewRule(
                    """{"reviewUrl":"/legacy","avatarRule":".avatar","contentRule":".text"}"""
                )
            )

        assertFalse(legacy.enabled)
        assertEquals("/legacy", legacy.reviewUrl)
        assertEquals(legacy, converters.stringToReviewRule(converters.reviewRuleToString(legacy)))
        assertFalse(
            BookSource(bookSourceUrl = "source", ruleReview = legacy)
                .equal(
                    BookSource(
                        bookSourceUrl = "source",
                        ruleReview = legacy.copy(contentRule = ".changed"),
                    )
                )
        )
    }

    @Test
    fun `pure detail content protocol preserves replies and media`() {
        val result =
            ReviewRuleParser.parseDetailPage(
                body =
                    """
                    {
                      "items": [
                        {
                          "id": "m1",
                          "avatar": "/avatar.png",
                          "name": "Alice",
                          "badges": ["author", "vip"],
                          "content": "{\"text\":\"Hello\",\"img\":\"/media.jpg\",\"audio\":\"/audio.mp3\",\"time\":\"now\",\"likeCount\":\"7.0\",\"replyCount\":1}",
                          "replies": [
                            {
                              "id": "r1",
                              "avatar": "/reply.png",
                              "name": "Bob",
                              "badges": "reader|top",
                              "content": "{\"text\":\"Reply\",\"replyToName\":\"Alice\",\"likeCount\":3}"
                            }
                          ]
                        },
                        {
                          "id": "m2",
                          "content": "{\"other\":\"kept\"}"
                        }
                      ]
                    }
                    """
                        .trimIndent(),
                rule =
                    ReviewRule(
                        detailListRule = "$.items",
                        detailIdRule = "$.id",
                        detailAvatarRule = "$.avatar",
                        detailNameRule = "$.name",
                        detailBadgeRule = "$.badges",
                        detailContentRule = "$.content",
                        replyListRule = "$.replies",
                        replyIdRule = "$.id",
                        replyAvatarRule = "$.avatar",
                        replyNameRule = "$.name",
                        replyBadgeRule = "$.badges",
                        replyContentRule = "$.content",
                    ),
                nextPageRule = null,
                baseUrl = chapter.url,
                source = source,
                book = book,
                chapter = chapter,
                context = EmptyCoroutineContext,
                paraIndex = "8",
                paraData = "key",
                page = "2",
            )

        assertEquals(null, result.nextPageUrl)
        assertEquals(2, result.items.size)
        with(result.items.first()) {
            assertEquals("m1", id)
            assertEquals("https://example.com/avatar.png", avatar)
            assertEquals("Alice", name)
            assertEquals(listOf("author", "vip"), badges)
            assertEquals("Hello", content)
            assertEquals("https://example.com/media.jpg", imageUrl)
            assertEquals("https://example.com/audio.mp3", audioUrl)
            assertEquals("now", time)
            assertEquals(7, likeCount)
            assertEquals(1, replyCount)
            assertEquals(1, replies.size)
            with(replies.single()) {
                assertEquals("r1", id)
                assertEquals("https://example.com/reply.png", avatar)
                assertEquals("Bob", name)
                assertEquals("Alice", replyToName)
                assertEquals(listOf("reader", "top"), badges)
                assertEquals("Reply", content)
                assertEquals(3, likeCount)
                assertEquals(null, replyCount)
            }
        }
        assertEquals("{\"other\":\"kept\"}", result.items[1].content)
    }
}
