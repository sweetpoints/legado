package io.legado.app.model.analyzeRule

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.ReviewRule
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original executable rule regressions, now run against the mandatory embedded V8 backend. */
@RunWith(AndroidJUnit4::class)
class ReviewRuleParserV8Test {
    private val source =
        RssSource(sourceUrl = "https://example.com", sourceName = "V8 review parser fixture")
    private val book = Book(bookUrl = "https://example.com/book", origin = source.sourceUrl)
    private val chapter = BookChapter(url = "https://example.com/chapter/1", bookUrl = book.bookUrl)

    @Test
    // parses JSON summary returned as a native array
    fun parsesJSONSummaryReturnedAsANativeArray(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
            val result =
                ReviewRuleParser.parseSummary(
                    body =
                        """
                        {
                          "items": [
                            {"index": 2, "data": "p2", "count": 3},
                            {"index": "4", "count": "5.0"},
                            {"index": 5, "count": 0}
                          ]
                        }
                        """
                            .trimIndent(),
                    rule =
                        ReviewRule(
                            summaryListRule = "@js:JSON.parse(src).items",
                            summaryParagraphIndexRule = "index",
                            summaryParagraphDataRule = "data",
                            summaryCountRule = "count",
                        ),
                    source = source,
                    book = book,
                    chapter = chapter,
                    baseUrl = chapter.url,
                    context = EmptyCoroutineContext,
                )

            assertNotNull(result)
            assertEquals(mapOf(2 to 3, 4 to 5), result!!.counts)
            assertEquals(mapOf(2 to "p2", 4 to "4"), result.keys)
        }
    }

    @Test
    // parses detail content protocol replies and local next page variables
    fun parsesDetailContentProtocolRepliesAndLocalNextPageVariables(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
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
                    nextPageRule = "@js:'/comments/' + paraIndex + '/' + paraData + '/' + page",
                    baseUrl = chapter.url,
                    source = source,
                    book = book,
                    chapter = chapter,
                    context = EmptyCoroutineContext,
                    paraIndex = "8",
                    paraData = "key",
                    page = "2",
                )

            assertEquals("https://example.com/comments/8/key/2", result.nextPageUrl)
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

    @Test
    // standalone reply list failures are retryable errors
    fun standaloneReplyListFailuresAreRetryableErrors(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
            assertThrows(Exception::class.java) {
                ReviewRuleParser.parseReplyPage(
                    body = "{}",
                    rule =
                        ReviewRule(
                            replyListRule = "@js:throw new Error('invalid reply list')",
                            replyContentRule = "$.content",
                        ),
                    baseUrl = chapter.url,
                    source = source,
                    book = book,
                    chapter = chapter,
                    context = EmptyCoroutineContext,
                    paraIndex = "1",
                    paraData = "0",
                    page = "1",
                )
            }
        }
    }

    @Test
    // detail JavaScript keeps grouped roots and replies when optional badges are missing
    fun detailJavaScriptKeepsGroupedRootsAndRepliesWhenOptionalBadgesAreMissing(): Unit =
        runBlocking {
            assertTrue(
                "Mandatory Flutter/V8 backend is required",
                BuildConfig.FLUTTER_SOURCE_ENGINE,
            )
            withContext(Dispatchers.IO) {
                val source =
                    RssSource(
                        sourceUrl = "review-js://grouped-detail",
                        sourceName = "Shared parser fixture",
                        jsLib = "function formatEmoji(value) { return value; }",
                    )
                val book = Book(bookUrl = "https://example.com/book", origin = source.sourceUrl)
                val chapter =
                    BookChapter(
                        url = "https://example.com/chapter/1",
                        bookUrl = book.bookUrl,
                    )
                val contentRule =
                    """
                    @js:
                                const J = (path) => java.getString(path);
                                JSON.stringify({text: formatEmoji(String(J("$.Content"))) });
                    """
                        .trimIndent()

                val result =
                    ReviewRuleParser.parseDetailPage(
                        body =
                            """
                            {
                              "Data": {
                                "DataList": [
                                  {
                                    "Id": "root-1",
                                    "RootReviewId": "root-1",
                                    "UserName": "Alice",
                                    "Content": "Root comment"
                                  },
                                  {
                                    "Id": "reply-1",
                                    "RootReviewId": "root-1",
                                    "UserName": "Bob",
                                    "Content": "Reply comment"
                                  }
                                ]
                              }
                            }
                            """
                                .trimIndent(),
                        rule =
                            ReviewRule(
                                detailListRule =
                                    """
                                    @js:
                                                        const data = JSON.parse(result).Data.DataList;
                                                        const roots = new Map();
                                                        for (const item of data) {
                                                          if (item.Id === item.RootReviewId) {
                                                            item.replyList = [];
                                                            roots.set(item.Id, item);
                                                          } else {
                                                            const root = roots.get(item.RootReviewId);
                                                            if (root) root.replyList.push(item);
                                                          }
                                                        }
                                                        [...roots.values()];
                                    """
                                        .trimIndent(),
                                detailIdRule = "$.Id",
                                detailNameRule = "$.UserName",
                                detailBadgeRule = "$.TitleInfoList[*].TitleImage",
                                detailContentRule = contentRule,
                                replyListRule = "replyList",
                                replyIdRule = "$.Id",
                                replyNameRule = "$.UserName",
                                replyBadgeRule = "$.TitleInfoList[*].TitleImage",
                                replyContentRule = contentRule,
                            ),
                        nextPageRule = null,
                        baseUrl = chapter.url,
                        source = source,
                        book = book,
                        chapter = chapter,
                        context = EmptyCoroutineContext,
                        paraIndex = "2",
                        paraData = "2",
                        page = "1",
                    )

                assertEquals(1, result.items.size)
                with(result.items.single()) {
                    assertEquals("root-1", id)
                    assertEquals("Alice", name)
                    assertEquals("Root comment", content)
                    assertTrue(badges.isEmpty())
                    assertEquals(1, replies.size)
                    with(replies.single()) {
                        assertEquals("reply-1", id)
                        assertEquals("Bob", name)
                        assertEquals("Reply comment", content)
                        assertTrue(badges.isEmpty())
                    }
                }
            }
        }

    @Test
    // detail JavaScript list fields execute against native objects
    fun detailJavaScriptListFieldsExecuteAgainstNativeObjects(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
            val result =
                ReviewRuleParser.parseDetailPage(
                    body =
                        """{"items":[{"name":"Alice","badges":["author","vip"],"content":"Hello"}]}""",
                    rule =
                        ReviewRule(
                            detailListRule = "@js:JSON.parse(result).items",
                            detailNameRule = "@js:result.name",
                            detailBadgeRule = "@js:result.badges",
                            detailContentRule = "@js:result.content",
                        ),
                    nextPageRule = null,
                    baseUrl = chapter.url,
                    source = source,
                    book = book,
                    chapter = chapter,
                    context = EmptyCoroutineContext,
                    paraIndex = "1",
                    paraData = "0",
                    page = "1",
                )

            assertEquals(listOf("author", "vip"), result.items.single().badges)
        }
    }

    @Test
    // local rule bindings are explicit and limited to review variables
    fun localRuleBindingsAreExplicitAndLimitedToReviewVariables(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
            val analyzeRule = AnalyzeRule().setContent("{}")
            assertEquals(
                "undefined|undefined|undefined",
                analyzeRule.evalJS("[typeof paraIndex, typeof paraData, typeof page].join('|')"),
            )

            analyzeRule
                .setLocal("custom", "hidden")
                .setLocal("paraIndex", "8")
                .setLocal("paraData", "")
                .setLocal("page", "3")
            assertEquals(
                "undefined|string:8|string:|number:3",
                analyzeRule.evalJS(
                    "[typeof custom, typeof paraIndex + ':' + paraIndex, " +
                        "typeof paraData + ':' + paraData, typeof page + ':' + page].join('|')"
                ),
            )
            assertEquals("hidden", analyzeRule.get("custom"))
        }
    }

    @Test
    // url extra parameters preserve info map and only add supplied globals
    fun urlExtraParametersPreserveInfoMapAndOnlyAddSuppliedGlobals(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
            val infoMap = mutableMapOf("token" to "ok")
            val ordinary = AnalyzeUrl("https://example.com", infoMap = infoMap)
            assertEquals(
                "undefined|undefined|ok",
                ordinary.evalJS("[typeof paraIndex, typeof paraData, infoMap['token']].join('|')"),
            )

            val reviewUrl =
                AnalyzeUrl(
                    "https://example.com",
                    page = 1,
                    infoMap = infoMap,
                    extraParams =
                        mapOf(
                            "paraIndex" to "8",
                            "paraData" to "key",
                            "reviewId" to "root-1",
                            "page" to "2",
                            "infoMap" to "shadow",
                        ),
                )
            assertEquals(
                "8|key|root-1|number:2|ok",
                reviewUrl.evalJS(
                    "[paraIndex, paraData, reviewId, typeof page + ':' + page, " +
                        "infoMap['token']].join('|')"
                ),
            )
        }
    }

    @Test
    // rule failures keep empty fallback and are recorded
    fun ruleFailuresKeepEmptyFallbackAndAreRecorded(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
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
            val brokenRule = "@js:missingReviewFunction()"

            AppLog.clear()
            try {
                val result =
                    ReviewRuleParser.parseSummary(
                        body = "{}",
                        rule =
                            ReviewRule(
                                summaryListRule = brokenRule,
                                summaryParagraphIndexRule = "index",
                            ),
                        source = source,
                        book = book,
                        chapter = chapter,
                        baseUrl = chapter.url,
                        context = EmptyCoroutineContext,
                    )

                assertEquals(emptyMap<Int, Int>(), result?.counts)
                assertTrue(
                    AppLog.logs.any {
                        it.second.contains("段评统计列表规则执行出错") && it.second.contains(brokenRule)
                    }
                )

                AppLog.clear()
                val detail =
                    ReviewRuleParser.parseDetailPage(
                        body = """{"items":[{},{}]}""",
                        rule =
                            ReviewRule(
                                detailListRule = "$.items",
                                detailNameRule = brokenRule,
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

                assertTrue(detail.items.isEmpty())
                assertEquals(
                    1,
                    AppLog.logs.count {
                        it.second.contains("段评规则执行出错") && it.second.contains(brokenRule)
                    },
                )

                AppLog.clear()
                ReviewRuleParser.parseDetailPage(
                    body = """{"items":[{}]}""",
                    rule =
                        ReviewRule(
                            detailListRule = "$.items",
                            detailNameRule = "$.broken[foo]",
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
                assertTrue(AppLog.logs.any { it.second.contains("$.broken[foo]") })
            } finally {
                AppLog.clear()
            }
        }
    }

    @Test
    // summary accepts a JSON array string returned by JavaScript
    fun summaryAcceptsAJSONArrayStringReturnedByJavaScript(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
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

            val result =
                ReviewRuleParser.parseSummary(
                    body = "{}",
                    rule =
                        ReviewRule(
                            summaryListRule = "@js:JSON.stringify([{index: 3, count: 4}])",
                            summaryParagraphIndexRule = "index",
                            summaryCountRule = "count",
                        ),
                    source = source,
                    book = book,
                    chapter = chapter,
                    baseUrl = chapter.url,
                    context = EmptyCoroutineContext,
                )

            assertEquals(mapOf(3 to 4), result?.counts)
            assertEquals(mapOf(3 to "3"), result?.keys)
        }
    }

    @Test
    // summary keeps every regex list match
    fun summaryKeepsEveryRegexListMatch(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
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

            val result =
                ReviewRuleParser.parseSummary(
                    body = "item:1:2 item:3:4",
                    rule =
                        ReviewRule(
                            summaryListRule = """:item:(\d+):(\d+)""",
                            summaryParagraphIndexRule = "@js:result[1]",
                            summaryCountRule = "@js:result[2]",
                        ),
                    source = source,
                    book = book,
                    chapter = chapter,
                    baseUrl = chapter.url,
                    context = EmptyCoroutineContext,
                )

            assertEquals(mapOf(1 to 2, 3 to 4), result?.counts)
        }
    }

    @Test
    // standalone reply rule is not evaluated against detail items
    fun standaloneReplyRuleIsNotEvaluatedAgainstDetailItems(): Unit = runBlocking {
        assertTrue("Mandatory Flutter/V8 backend is required", BuildConfig.FLUTTER_SOURCE_ENGINE)
        withContext(Dispatchers.IO) {
            val result =
                ReviewRuleParser.parseDetailPage(
                    body = """{"items":[{"id":"m1","content":"Comment"}]}""",
                    rule =
                        ReviewRule(
                            reviewQuoteUrl = "/replies",
                            detailListRule = "$.items",
                            detailIdRule = "$.id",
                            detailContentRule = "$.content",
                            replyListRule = "@js:java.put('inlineReplyRule', 'evaluated');[]",
                            replyContentRule = "$.content",
                        ),
                    nextPageRule = null,
                    baseUrl = chapter.url,
                    source = source,
                    book = book,
                    chapter = chapter,
                    context = EmptyCoroutineContext,
                    paraIndex = "1",
                    paraData = "0",
                    page = "1",
                )

            assertEquals(1, result.items.size)
            assertTrue(result.items.single().replies.isEmpty())
            assertFalse(chapter.variableMap.containsKey("inlineReplyRule"))
        }
    }
}
