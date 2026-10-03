package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaItem
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import kotlinx.coroutines.currentCoroutineContext

internal class AppReviewDetailStore(context: Context) : ReviewDetailStore {
    private val context = context.applicationContext

    override suspend fun isCurrent(key: ReviewDetailKey): Boolean {
        val source = ReadBook.bookSource ?: return false
        val book = ReadBook.book ?: return false
        return source.getKey() == key.sourceKey &&
            book.bookUrl == key.bookUrl &&
            if (source.isJsSource()) source.mainJs.hashCode() == key.ruleHash
            else source.ruleReview?.let { it.enabled && it.hashCode() == key.ruleHash } == true
    }

    override suspend fun context(key: ReviewDetailKey): ReviewDetailContext? {
        if (!isCurrent(key)) return null
        val source = ReadBook.bookSource?.copy() ?: return null
        val book = ReadBook.book?.copy() ?: return null
        val chapter = appDb.bookChapterDao.getChapter(key.bookUrl, key.chapterIndex) ?: return null
        if (!isCurrent(key)) return null
        return ReviewDetailContext(source, book, chapter)
    }

    override suspend fun detail(
        context: ReviewDetailContext,
        key: ReviewDetailKey,
        page: Int,
        nextUrl: String?,
    ): ReviewDetailPage? {
        val (source, book, chapter) = context
        if (source.isJsSource()) {
            val result =
                JsSourceReview.getReviewDetailAwait(
                    source,
                    book,
                    chapter,
                    key.paragraphNum,
                    key.paragraphData,
                    page,
                ) ?: return null
            return ReviewDetailPage(
                result.items.map { it.toReviewComment() },
                result.nextPageUrl,
                true,
                JsSourceReview.hasReviewRepliesCapability(source),
            )
        }
        val rule = source.ruleReview ?: return null
        val firstUrl = rule.reviewDetailUrl?.takeIf { it.isNotBlank() } ?: return null
        val nextRule = rule.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
        val next = nextUrl?.takeIf { it.isNotBlank() }
        if (page > 1 && next == null && nextRule == null) return null
        val url = if (page > 1) next ?: nextRule ?: firstUrl else firstUrl
        if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank())
            return null
        val coroutineContext = currentCoroutineContext()
        val analyze =
            AnalyzeUrl(
                url,
                page = page,
                extraParams =
                    mapOf(
                        "paraIndex" to key.paragraphNum.toString(),
                        "paraData" to key.paragraphData,
                        "page" to page.toString(),
                    ),
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext,
            )
        val body = analyze.getStrResponseAwait(useWebView = false).body.orEmpty()
        val result =
            ReviewRuleParser.parseDetailPage(
                body,
                rule,
                nextRule,
                analyze.url,
                source,
                book,
                chapter,
                coroutineContext,
                key.paragraphNum.toString(),
                key.paragraphData,
                page.toString(),
            )
        return ReviewDetailPage(
            result.items.map { it.toReviewComment() },
            result.nextPageUrl,
            nextRule != null,
            !rule.reviewQuoteUrl.isNullOrBlank() &&
                !rule.replyListRule.isNullOrBlank() &&
                !rule.replyContentRule.isNullOrBlank(),
        )
    }

    override suspend fun replies(
        context: ReviewDetailContext,
        key: ReviewDetailKey,
        reviewId: String,
        page: Int,
    ): ReviewReplyPage? {
        val (source, book, chapter) = context
        if (source.isJsSource()) {
            val replies =
                JsSourceReview.getReviewRepliesAwait(
                    source,
                    book,
                    chapter,
                    key.paragraphNum,
                    key.paragraphData,
                    reviewId,
                    page,
                ) ?: return null
            return ReviewReplyPage(replies.map { it.toReviewComment() }, page)
        }
        val rule = source.ruleReview ?: return null
        val url = rule.reviewQuoteUrl?.takeIf { it.isNotBlank() } ?: return null
        if (rule.replyListRule.isNullOrBlank() || rule.replyContentRule.isNullOrBlank()) return null
        val coroutineContext = currentCoroutineContext()
        val analyze =
            AnalyzeUrl(
                url,
                page = page,
                extraParams =
                    mapOf(
                        "paraIndex" to key.paragraphNum.toString(),
                        "paraData" to key.paragraphData,
                        "reviewId" to reviewId,
                        "page" to page.toString(),
                    ),
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext,
            )
        val body =
            analyze.getStrResponseAwait(useWebView = false).body?.takeIf { it.isNotBlank() }
                ?: error(this.context.getString(R.string.content_empty))
        val replies =
            ReviewRuleParser.parseReplyPage(
                body,
                rule,
                analyze.url,
                source,
                book,
                chapter,
                coroutineContext,
                key.paragraphNum.toString(),
                key.paragraphData,
                page.toString(),
            )
        return ReviewReplyPage(replies.map { it.toReviewComment() }, page)
    }

    override suspend fun mediaItem(context: ReviewDetailContext, url: String): MediaItem =
        AnalyzeUrl(url, source = context.source).getMediaItem()

    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(this.context.cacheDir, "review-details/$session.json"))
    }

    override suspend fun restore(session: String): ReviewDetailSnapshot? {
        val target = file(session)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        return GSON.fromJsonObject<ReviewDetailSnapshot>(
                target.openRead().bufferedReader().use { it.readText() }
            )
            .getOrThrow()
    }

    override suspend fun stage(session: String, snapshot: ReviewDetailSnapshot) {
        val target = file(session)
        val stream = target.startWrite()
        try {
            stream.write(GSON.toJson(snapshot).toByteArray())
            target.finishWrite(stream)
        } catch (error: Throwable) {
            target.failWrite(stream)
            throw error
        }
    }
}
