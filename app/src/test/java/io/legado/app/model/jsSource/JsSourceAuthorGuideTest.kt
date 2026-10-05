package io.legado.app.model.jsSource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsSourceAuthorGuideTest {

    private val guide by lazy {
        val document = source("app/src/main/assets/web/help/md/jsHelp.md")
        val startMarker = "<!-- js-source-guide:start -->"
        val endMarker = "<!-- js-source-guide:end -->"
        assertEquals("Guide start marker count", 1, document.countOccurrences(startMarker))
        assertEquals("Guide end marker count", 1, document.countOccurrences(endMarker))
        document.substringAfter(startMarker).substringBefore(endMarker)
    }

    @Test
    fun `guide describes current JavaScript source contracts`() {
        val requiredText = listOf(
            "`config` 是脚本声明的普通配置对象",
            "`source` 与 `sourceApi` 是书源 JSON snapshot",
            "旧版脚本",
            "无关或未定义的 `config` 不影响旧版 `source` 导入",
            "function explore(url, page)",
            "function getContent(chapter, book, nextChapterUrl)",
            "`4` 视频",
            "`loginUi` 非空时必选",
            "`exploreUrl` 非空时必选",
            "文件源必选",
            "`downloadUrls`",
            "java.md5Encode或java.digestHex",
            "只选择一个 JavaScript 书源导出或分享",
            "getReviewSummary(chapter, book)",
            "getReviewDetail(chapter, book, paraIndex, paraData, page)",
            "getReviewReplies(chapter, book, paraIndex, paraData, reviewId, page)",
            "replyCount",
            "页面从 `1` 开始",
            "nextPageUrl",
            "{text, replyToName, img, audio, time, likeCount, replyCount}",
            "`badge` 可返回字符串或字符串数组",
        )

        requiredText.forEach { text ->
            assertTrue("Missing current JS source contract: $text", guide.contains(text))
        }
        assertTrue(guide.contains("只有同时声明 `getReviewSummary` 和 `getReviewDetail`"))
        assertTrue(guide.contains("可选声明 `getReviewReplies`"))
    }

    @Test
    fun `guide documents explore URL handling`() {
        val template = source("app/src/main/assets/js_source_template.js")

        listOf("换行或 `&&` 分隔", "`url` 原样传入", "不替换 `{{page}}`").forEach { text ->
            assertTrue("Missing explore URL contract: $text", guide.contains(text))
        }
        listOf("换行或 && 分隔", "url 原样传入", "不会替换 {{page}}").forEach { text ->
            assertTrue("Missing explore URL template hint: $text", template.contains(text))
        }
    }

    @Test
    fun `guide documents native JavaScript string boundaries`() {
        val requiredText = listOf(
            "`typeof chapter.title`",
            "`chapter.title.length`",
            "`chapter.tag ? \"T\" : \"F\"`",
            "`chapter.title === \"第1章\"`",
            "`chapter.url.replace(/b/, \"X\")`",
            "`chapter.url.split(\"/\").length`",
        )

        requiredText.forEach { text ->
            assertTrue("Missing native string boundary documentation: $text", guide.contains(text))
        }
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun String.countOccurrences(value: String): Int {
        return Regex(Regex.escape(value)).findAll(this).count()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
