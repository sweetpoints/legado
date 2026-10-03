package io.legado.app.ui.book.source.edit

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.RuleComplete

@Keep
internal data class BookSourceEditField(
    val key: String,
    val value: String,
    val labelResource: Int = 0,
    val label: String? = null,
    val selectionStart: Int = 0,
    val selectionEnd: Int = selectionStart,
) {
    fun boundedSelection(): BookSourceEditField =
        copy(
            selectionStart = selectionStart.coerceIn(0, value.length),
            selectionEnd = selectionEnd.coerceIn(0, value.length),
        )
}

@Keep
internal data class BookSourceEditOptions(
    val enabled: Boolean,
    val enabledExplore: Boolean,
    val enabledCookieJar: Boolean,
    val enabledReview: Boolean,
    val type: Int,
    val eventListener: Boolean,
    val customButton: Boolean,
)

@Keep
internal data class BookSourceEditForm(
    val options: BookSourceEditOptions,
    val tabs: List<List<BookSourceEditField>>,
) {
    fun hasLogin(): Boolean =
        BookSource(
                loginUrl = field(0, "loginUrl")?.value,
                loginUi = field(0, "loginUi")?.value,
            )
            .hasLogin()

    fun field(tab: Int, key: String): BookSourceEditField? =
        tabs.getOrNull(tab)?.find { it.key == key }

    fun updateField(
        tab: Int,
        key: String,
        text: String,
        selectionStart: Int? = null,
        selectionEnd: Int? = null,
    ): BookSourceEditForm {
        return copy(
            tabs =
                tabs.mapIndexed { index, fields ->
                    if (index != tab) fields
                    else
                        fields.map { field ->
                            if (field.key != key) field
                            else
                                field
                                    .copy(
                                        value = text,
                                        selectionStart = selectionStart ?: field.selectionStart,
                                        selectionEnd = selectionEnd ?: field.selectionEnd,
                                    )
                                    .boundedSelection()
                        }
                }
        )
    }

    fun insert(tab: Int, key: String, insertion: String): BookSourceEditForm {
        if (insertion.isEmpty()) return this
        val target = field(tab, key)?.boundedSelection() ?: return this
        val start = minOf(target.selectionStart, target.selectionEnd)
        val end = maxOf(target.selectionStart, target.selectionEnd)
        val updatedText = target.value.replaceRange(start, end, insertion)
        val updatedCursor = start + insertion.length
        return updateField(tab, key, updatedText, updatedCursor, updatedCursor)
    }
}

internal fun projectBookSourceEditForm(source: BookSource): BookSourceEditForm {
    return BookSourceEditForm(
        options =
            BookSourceEditOptions(
                enabled = source.enabled,
                enabledExplore = source.enabledExplore,
                enabledCookieJar = source.enabledCookieJar ?: false,
                enabledReview = source.ruleReview?.enabled ?: false,
                type = source.bookSourceType,
                eventListener = source.eventListener,
                customButton = source.customButton,
            ),
        tabs =
            listOf(
                sourceFields(source),
                searchFields(source),
                exploreFields(source),
                infoFields(source),
                tocFields(source),
                contentFields(source),
                reviewFields(source),
            ),
    )
}

internal fun materializeBookSourceEditForm(
    original: BookSource,
    form: BookSourceEditForm,
    autoComplete: Boolean = false,
): BookSource {
    val source = original.copy()
    val options = form.options
    source.enabled = options.enabled
    source.enabledExplore = options.enabledExplore
    source.enabledCookieJar = options.enabledCookieJar
    source.bookSourceType = options.type
    source.eventListener = options.eventListener
    source.customButton = options.customButton
    val searchRule = SearchRule()
    val exploreRule = ExploreRule()
    val infoRule = BookInfoRule()
    val tocRule = TocRule()
    val contentRule = ContentRule()
    val reviewRule = source.ruleReview?.copy() ?: ReviewRule()
    reviewRule.enabled = options.enabledReview
    val completion = BookSourceRuleCompletion(autoComplete)
    applyBaseFields(source, form.tabs[0])
    applySearchFields(source, searchRule, form.tabs[1], completion)
    applyExploreFields(source, exploreRule, form.tabs[2], completion)
    applyInfoFields(infoRule, form.tabs[3], completion)
    applyTocFields(tocRule, form.tabs[4], completion)
    applyContentFields(contentRule, form.tabs[5], completion)
    applyReviewFields(reviewRule, form.tabs[6])
    source.ruleSearch = searchRule
    source.ruleExplore = exploreRule
    source.ruleBookInfo = infoRule
    source.ruleToc = tocRule
    source.ruleContent = contentRule
    source.ruleReview = reviewRule.takeIf {
        original.ruleReview != null ||
            it.enabled ||
            form.tabs[6].any { field -> field.value.isNotBlank() }
    }
    return source
}

private class BookSourceRuleCompletion(private val enabled: Boolean) {
    fun apply(rule: String?, previousRule: String? = null, type: Int = 1): String? {
        return if (enabled) RuleComplete.autoComplete(rule, previousRule, type) else rule
    }
}

private fun sourceFields(source: BookSource): List<BookSourceEditField> {
    return listOf(
        BookSourceEditField(
            "bookSourceUrl",
            source.bookSourceUrl.orEmpty(),
            labelResource = R.string.source_url,
        ),
        BookSourceEditField(
            "bookSourceName",
            source.bookSourceName.orEmpty(),
            labelResource = R.string.source_name,
        ),
        BookSourceEditField(
            "bookSourceGroup",
            source.bookSourceGroup.orEmpty(),
            labelResource = R.string.source_group,
        ),
        BookSourceEditField(
            "bookSourceComment",
            source.bookSourceComment.orEmpty(),
            labelResource = R.string.comment,
        ),
        BookSourceEditField(
            "loginUrl",
            source.loginUrl.orEmpty(),
            labelResource = R.string.login_url,
        ),
        BookSourceEditField("loginUi", source.loginUi.orEmpty(), labelResource = R.string.login_ui),
        BookSourceEditField(
            "loginCheckJs",
            source.loginCheckJs.orEmpty(),
            labelResource = R.string.login_check_js,
        ),
        BookSourceEditField(
            "coverDecodeJs",
            source.coverDecodeJs.orEmpty(),
            labelResource = R.string.cover_decode_js,
        ),
        BookSourceEditField(
            "bookUrlPattern",
            source.bookUrlPattern.orEmpty(),
            labelResource = R.string.book_url_pattern,
        ),
        BookSourceEditField(
            "header",
            source.header.orEmpty(),
            labelResource = R.string.source_http_header,
        ),
        BookSourceEditField(
            "variableComment",
            source.variableComment.orEmpty(),
            labelResource = R.string.variable_comment,
        ),
        BookSourceEditField(
            "concurrentRate",
            source.concurrentRate.orEmpty(),
            labelResource = R.string.concurrent_rate,
        ),
        BookSourceEditField("jsLib", source.jsLib.orEmpty(), label = "jsLib"),
    )
}

private fun searchFields(source: BookSource): List<BookSourceEditField> {
    val searchRule = source.ruleSearch ?: SearchRule()
    return listOf(
        BookSourceEditField(
            "searchUrl",
            source.searchUrl.orEmpty(),
            labelResource = R.string.r_search_url,
        ),
        BookSourceEditField(
            "checkKeyWord",
            searchRule.checkKeyWord.orEmpty(),
            labelResource = R.string.check_key_word,
        ),
        BookSourceEditField(
            "bookList",
            searchRule.bookList.orEmpty(),
            labelResource = R.string.r_book_list,
        ),
        BookSourceEditField(
            "name",
            searchRule.name.orEmpty(),
            labelResource = R.string.r_book_name,
        ),
        BookSourceEditField(
            "author",
            searchRule.author.orEmpty(),
            labelResource = R.string.r_author,
        ),
        BookSourceEditField(
            "kind",
            searchRule.kind.orEmpty(),
            labelResource = R.string.rule_book_kind,
        ),
        BookSourceEditField(
            "wordCount",
            searchRule.wordCount.orEmpty(),
            labelResource = R.string.rule_word_count,
        ),
        BookSourceEditField(
            "lastChapter",
            searchRule.lastChapter.orEmpty(),
            labelResource = R.string.rule_last_chapter,
        ),
        BookSourceEditField(
            "intro",
            searchRule.intro.orEmpty(),
            labelResource = R.string.rule_book_intro,
        ),
        BookSourceEditField(
            "coverUrl",
            searchRule.coverUrl.orEmpty(),
            labelResource = R.string.rule_cover_url,
        ),
        BookSourceEditField(
            "bookUrl",
            searchRule.bookUrl.orEmpty(),
            labelResource = R.string.r_book_url,
        ),
    )
}

private fun exploreFields(source: BookSource): List<BookSourceEditField> {
    val exploreRule = source.ruleExplore ?: ExploreRule()
    return listOf(
        BookSourceEditField(
            "exploreUrl",
            source.exploreUrl.orEmpty(),
            labelResource = R.string.r_find_url,
        ),
        BookSourceEditField(
            "bookList",
            exploreRule.bookList.orEmpty(),
            labelResource = R.string.r_book_list,
        ),
        BookSourceEditField(
            "name",
            exploreRule.name.orEmpty(),
            labelResource = R.string.r_book_name,
        ),
        BookSourceEditField(
            "author",
            exploreRule.author.orEmpty(),
            labelResource = R.string.r_author,
        ),
        BookSourceEditField(
            "kind",
            exploreRule.kind.orEmpty(),
            labelResource = R.string.rule_book_kind,
        ),
        BookSourceEditField(
            "wordCount",
            exploreRule.wordCount.orEmpty(),
            labelResource = R.string.rule_word_count,
        ),
        BookSourceEditField(
            "lastChapter",
            exploreRule.lastChapter.orEmpty(),
            labelResource = R.string.rule_last_chapter,
        ),
        BookSourceEditField(
            "intro",
            exploreRule.intro.orEmpty(),
            labelResource = R.string.rule_book_intro,
        ),
        BookSourceEditField(
            "coverUrl",
            exploreRule.coverUrl.orEmpty(),
            labelResource = R.string.rule_cover_url,
        ),
        BookSourceEditField(
            "bookUrl",
            exploreRule.bookUrl.orEmpty(),
            labelResource = R.string.r_book_url,
        ),
    )
}

private fun infoFields(source: BookSource): List<BookSourceEditField> {
    val infoRule = source.ruleBookInfo ?: BookInfoRule()
    return listOf(
        BookSourceEditField(
            "init",
            infoRule.init.orEmpty(),
            labelResource = R.string.rule_book_info_init,
        ),
        BookSourceEditField("name", infoRule.name.orEmpty(), labelResource = R.string.r_book_name),
        BookSourceEditField("author", infoRule.author.orEmpty(), labelResource = R.string.r_author),
        BookSourceEditField(
            "kind",
            infoRule.kind.orEmpty(),
            labelResource = R.string.rule_book_kind,
        ),
        BookSourceEditField(
            "wordCount",
            infoRule.wordCount.orEmpty(),
            labelResource = R.string.rule_word_count,
        ),
        BookSourceEditField(
            "lastChapter",
            infoRule.lastChapter.orEmpty(),
            labelResource = R.string.rule_last_chapter,
        ),
        BookSourceEditField(
            "intro",
            infoRule.intro.orEmpty(),
            labelResource = R.string.rule_book_intro,
        ),
        BookSourceEditField(
            "coverUrl",
            infoRule.coverUrl.orEmpty(),
            labelResource = R.string.rule_cover_url,
        ),
        BookSourceEditField(
            "tocUrl",
            infoRule.tocUrl.orEmpty(),
            labelResource = R.string.rule_toc_url,
        ),
        BookSourceEditField(
            "canReName",
            infoRule.canReName.orEmpty(),
            labelResource = R.string.rule_can_re_name,
        ),
        BookSourceEditField(
            "downloadUrls",
            infoRule.downloadUrls.orEmpty(),
            labelResource = R.string.download_url_rule,
        ),
    )
}

private fun tocFields(source: BookSource): List<BookSourceEditField> {
    val tocRule = source.ruleToc ?: TocRule()
    return listOf(
        BookSourceEditField(
            "preUpdateJs",
            tocRule.preUpdateJs.orEmpty(),
            labelResource = R.string.pre_update_js,
        ),
        BookSourceEditField(
            "chapterList",
            tocRule.chapterList.orEmpty(),
            labelResource = R.string.rule_chapter_list,
        ),
        BookSourceEditField(
            "chapterName",
            tocRule.chapterName.orEmpty(),
            labelResource = R.string.rule_chapter_name,
        ),
        BookSourceEditField(
            "chapterUrl",
            tocRule.chapterUrl.orEmpty(),
            labelResource = R.string.rule_chapter_url,
        ),
        BookSourceEditField(
            "formatJs",
            tocRule.formatJs.orEmpty(),
            labelResource = R.string.format_js_rule,
        ),
        BookSourceEditField(
            "isVolume",
            tocRule.isVolume.orEmpty(),
            labelResource = R.string.rule_is_volume,
        ),
        BookSourceEditField(
            "updateTime",
            tocRule.updateTime.orEmpty(),
            labelResource = R.string.rule_update_time,
        ),
        BookSourceEditField("isVip", tocRule.isVip.orEmpty(), labelResource = R.string.rule_is_vip),
        BookSourceEditField("isPay", tocRule.isPay.orEmpty(), labelResource = R.string.rule_is_pay),
        BookSourceEditField(
            "nextTocUrl",
            tocRule.nextTocUrl.orEmpty(),
            labelResource = R.string.rule_next_toc_url,
        ),
    )
}

private fun contentFields(source: BookSource): List<BookSourceEditField> {
    val contentRule = source.ruleContent ?: ContentRule()
    return listOf(
        BookSourceEditField(
            "content",
            contentRule.content.orEmpty(),
            labelResource = R.string.rule_book_content,
        ),
        BookSourceEditField(
            "nextContentUrl",
            contentRule.nextContentUrl.orEmpty(),
            labelResource = R.string.rule_next_content,
        ),
        BookSourceEditField(
            "subContent",
            contentRule.subContent.orEmpty(),
            labelResource = R.string.rule_sub_content,
        ),
        BookSourceEditField(
            "replaceRegex",
            contentRule.replaceRegex.orEmpty(),
            labelResource = R.string.rule_replace_regex,
        ),
        BookSourceEditField(
            "title",
            contentRule.title.orEmpty(),
            labelResource = R.string.rule_chapter_name,
        ),
        BookSourceEditField(
            "sourceRegex",
            contentRule.sourceRegex.orEmpty(),
            labelResource = R.string.rule_source_regex,
        ),
        BookSourceEditField(
            "imageStyle",
            contentRule.imageStyle.orEmpty(),
            labelResource = R.string.rule_image_style,
        ),
        BookSourceEditField(
            "imageDecode",
            contentRule.imageDecode.orEmpty(),
            labelResource = R.string.rule_image_decode,
        ),
        BookSourceEditField(
            "webJs",
            contentRule.webJs.orEmpty(),
            labelResource = R.string.rule_web_js,
        ),
        BookSourceEditField(
            "payAction",
            contentRule.payAction.orEmpty(),
            labelResource = R.string.rule_pay_action,
        ),
        BookSourceEditField(
            "callBackJs",
            contentRule.callBackJs.orEmpty(),
            labelResource = R.string.rule_call_back,
        ),
        BookSourceEditField(
            "contentBatch",
            contentRule.contentBatch.orEmpty(),
            labelResource = R.string.rule_content_batch,
        ),
        BookSourceEditField(
            "maxBatchSize",
            contentRule.maxBatchSize?.toString().orEmpty(),
            labelResource = R.string.rule_max_batch_size,
        ),
    )
}

private fun reviewFields(source: BookSource): List<BookSourceEditField> {
    val reviewRule = source.ruleReview ?: ReviewRule()
    return listOf(
        BookSourceEditField(
            "reviewSummaryUrl",
            reviewRule.reviewSummaryUrl.orEmpty(),
            labelResource = R.string.rule_review_summary_url,
        ),
        BookSourceEditField(
            "summaryListRule",
            reviewRule.summaryListRule.orEmpty(),
            labelResource = R.string.rule_review_summary_list,
        ),
        BookSourceEditField(
            "summaryParagraphIndexRule",
            reviewRule.summaryParagraphIndexRule.orEmpty(),
            labelResource = R.string.rule_review_summary_id,
        ),
        BookSourceEditField(
            "summaryCountRule",
            reviewRule.summaryCountRule.orEmpty(),
            labelResource = R.string.rule_review_summary_count,
        ),
        BookSourceEditField(
            "summaryParagraphDataRule",
            reviewRule.summaryParagraphDataRule.orEmpty(),
            labelResource = R.string.rule_review_summary_key,
        ),
        BookSourceEditField(
            "reviewDetailUrl",
            reviewRule.reviewDetailUrl.orEmpty(),
            labelResource = R.string.rule_review_detail_url,
        ),
        BookSourceEditField(
            "reviewDetailNextPageUrl",
            reviewRule.reviewDetailNextPageUrl.orEmpty(),
            labelResource = R.string.rule_review_detail_next_url,
        ),
        BookSourceEditField(
            "detailListRule",
            reviewRule.detailListRule.orEmpty(),
            labelResource = R.string.rule_review_detail_list,
        ),
        BookSourceEditField(
            "detailIdRule",
            reviewRule.detailIdRule.orEmpty(),
            labelResource = R.string.rule_review_detail_id,
        ),
        BookSourceEditField(
            "detailAvatarRule",
            reviewRule.detailAvatarRule.orEmpty(),
            labelResource = R.string.rule_review_detail_avatar,
        ),
        BookSourceEditField(
            "detailNameRule",
            reviewRule.detailNameRule.orEmpty(),
            labelResource = R.string.rule_review_detail_name,
        ),
        BookSourceEditField(
            "detailBadgeRule",
            reviewRule.detailBadgeRule.orEmpty(),
            labelResource = R.string.rule_review_detail_badge,
        ),
        BookSourceEditField(
            "detailContentRule",
            reviewRule.detailContentRule.orEmpty(),
            labelResource = R.string.rule_review_detail_content,
        ),
        BookSourceEditField(
            "reviewQuoteUrl",
            reviewRule.reviewQuoteUrl.orEmpty(),
            labelResource = R.string.rule_review_quote,
        ),
        BookSourceEditField(
            "replyListRule",
            reviewRule.replyListRule.orEmpty(),
            labelResource = R.string.rule_review_reply_list,
        ),
        BookSourceEditField(
            "replyIdRule",
            reviewRule.replyIdRule.orEmpty(),
            labelResource = R.string.rule_review_reply_id,
        ),
        BookSourceEditField(
            "replyAvatarRule",
            reviewRule.replyAvatarRule.orEmpty(),
            labelResource = R.string.rule_review_reply_avatar,
        ),
        BookSourceEditField(
            "replyNameRule",
            reviewRule.replyNameRule.orEmpty(),
            labelResource = R.string.rule_review_reply_name,
        ),
        BookSourceEditField(
            "replyBadgeRule",
            reviewRule.replyBadgeRule.orEmpty(),
            labelResource = R.string.rule_review_reply_badge,
        ),
        BookSourceEditField(
            "replyContentRule",
            reviewRule.replyContentRule.orEmpty(),
            labelResource = R.string.rule_review_reply_content,
        ),
    )
}

private fun applyBaseFields(source: BookSource, fields: List<BookSourceEditField>) {
    fields.forEach { field ->
        val normalizedValue = field.value.takeIf { value -> value.isNotBlank() }
        when (field.key) {
            "bookSourceUrl" -> source.bookSourceUrl = normalizedValue ?: ""
            "bookSourceName" -> source.bookSourceName = normalizedValue ?: ""
            "bookSourceGroup" -> source.bookSourceGroup = normalizedValue
            "loginUrl" -> source.loginUrl = normalizedValue
            "loginUi" -> source.loginUi = normalizedValue
            "loginCheckJs" -> source.loginCheckJs = normalizedValue
            "coverDecodeJs" -> source.coverDecodeJs = normalizedValue
            "bookUrlPattern" -> source.bookUrlPattern = normalizedValue
            "header" -> source.header = normalizedValue
            "bookSourceComment" -> source.bookSourceComment = normalizedValue
            "concurrentRate" -> source.concurrentRate = normalizedValue
            "variableComment" -> source.variableComment = normalizedValue
            "jsLib" -> source.jsLib = normalizedValue
        }
    }
}

private fun applySearchFields(
    source: BookSource,
    searchRule: SearchRule,
    fields: List<BookSourceEditField>,
    completion: BookSourceRuleCompletion,
) {
    fields.forEach { field ->
        val normalizedValue = field.value.takeIf { value -> value.isNotBlank() }
        when (field.key) {
            "searchUrl" -> source.searchUrl = normalizedValue
            "checkKeyWord" -> searchRule.checkKeyWord = normalizedValue
            "bookList" -> searchRule.bookList = normalizedValue
            "name" -> searchRule.name = completion.apply(normalizedValue, searchRule.bookList)

            "author" -> searchRule.author = completion.apply(normalizedValue, searchRule.bookList)

            "kind" -> searchRule.kind = completion.apply(normalizedValue, searchRule.bookList)

            "intro" -> searchRule.intro = completion.apply(normalizedValue, searchRule.bookList)

            "wordCount" ->
                searchRule.wordCount = completion.apply(normalizedValue, searchRule.bookList)

            "lastChapter" ->
                searchRule.lastChapter = completion.apply(normalizedValue, searchRule.bookList)

            "coverUrl" ->
                searchRule.coverUrl = completion.apply(normalizedValue, searchRule.bookList, 3)

            "bookUrl" ->
                searchRule.bookUrl = completion.apply(normalizedValue, searchRule.bookList, 2)
        }
    }
}

private fun applyExploreFields(
    source: BookSource,
    exploreRule: ExploreRule,
    fields: List<BookSourceEditField>,
    completion: BookSourceRuleCompletion,
) {
    fields.forEach { field ->
        val normalizedValue = field.value.takeIf { value -> value.isNotBlank() }
        when (field.key) {
            "exploreUrl" -> source.exploreUrl = normalizedValue
            "bookList" -> exploreRule.bookList = normalizedValue
            "name" -> exploreRule.name = completion.apply(normalizedValue, exploreRule.bookList)

            "author" -> exploreRule.author = completion.apply(normalizedValue, exploreRule.bookList)

            "kind" -> exploreRule.kind = completion.apply(normalizedValue, exploreRule.bookList)

            "intro" -> exploreRule.intro = completion.apply(normalizedValue, exploreRule.bookList)

            "wordCount" ->
                exploreRule.wordCount = completion.apply(normalizedValue, exploreRule.bookList)

            "lastChapter" ->
                exploreRule.lastChapter = completion.apply(normalizedValue, exploreRule.bookList)

            "coverUrl" ->
                exploreRule.coverUrl = completion.apply(normalizedValue, exploreRule.bookList, 3)

            "bookUrl" ->
                exploreRule.bookUrl = completion.apply(normalizedValue, exploreRule.bookList, 2)
        }
    }
}

private fun applyInfoFields(
    bookInfoRule: BookInfoRule,
    fields: List<BookSourceEditField>,
    completion: BookSourceRuleCompletion,
) {
    fields.forEach { field ->
        val normalizedValue = field.value.takeIf { value -> value.isNotBlank() }
        when (field.key) {
            "init" -> bookInfoRule.init = normalizedValue
            "name" -> bookInfoRule.name = completion.apply(normalizedValue, bookInfoRule.init)
            "author" -> bookInfoRule.author = completion.apply(normalizedValue, bookInfoRule.init)

            "kind" -> bookInfoRule.kind = completion.apply(normalizedValue, bookInfoRule.init)

            "intro" -> bookInfoRule.intro = completion.apply(normalizedValue, bookInfoRule.init)

            "wordCount" ->
                bookInfoRule.wordCount = completion.apply(normalizedValue, bookInfoRule.init)

            "lastChapter" ->
                bookInfoRule.lastChapter = completion.apply(normalizedValue, bookInfoRule.init)

            "coverUrl" ->
                bookInfoRule.coverUrl = completion.apply(normalizedValue, bookInfoRule.init, 3)

            "tocUrl" ->
                bookInfoRule.tocUrl = completion.apply(normalizedValue, bookInfoRule.init, 2)

            "canReName" -> bookInfoRule.canReName = normalizedValue
            "downloadUrls" ->
                bookInfoRule.downloadUrls = completion.apply(normalizedValue, bookInfoRule.init)
        }
    }
}

private fun applyTocFields(
    tocRule: TocRule,
    fields: List<BookSourceEditField>,
    completion: BookSourceRuleCompletion,
) {
    fields.forEach { field ->
        val normalizedValue = field.value.takeIf { value -> value.isNotBlank() }
        when (field.key) {
            "preUpdateJs" -> tocRule.preUpdateJs = normalizedValue
            "chapterList" -> tocRule.chapterList = normalizedValue
            "chapterName" ->
                tocRule.chapterName = completion.apply(normalizedValue, tocRule.chapterList)

            "chapterUrl" ->
                tocRule.chapterUrl = completion.apply(normalizedValue, tocRule.chapterList, 2)

            "formatJs" -> tocRule.formatJs = normalizedValue
            "isVolume" -> tocRule.isVolume = normalizedValue
            "updateTime" -> tocRule.updateTime = normalizedValue
            "isVip" -> tocRule.isVip = normalizedValue
            "isPay" -> tocRule.isPay = normalizedValue
            "nextTocUrl" ->
                tocRule.nextTocUrl = completion.apply(normalizedValue, tocRule.chapterList, 2)
        }
    }
}

private fun applyContentFields(
    contentRule: ContentRule,
    fields: List<BookSourceEditField>,
    completion: BookSourceRuleCompletion,
) {
    fields.forEach { field ->
        val normalizedValue = field.value.takeIf { value -> value.isNotBlank() }
        when (field.key) {
            "content" -> contentRule.content = completion.apply(normalizedValue)
            "nextContentUrl" ->
                contentRule.nextContentUrl = completion.apply(normalizedValue, type = 2)
            "subContent" -> contentRule.subContent = completion.apply(normalizedValue)
            "title" -> contentRule.title = completion.apply(normalizedValue)

            "webJs" -> contentRule.webJs = normalizedValue
            "sourceRegex" -> contentRule.sourceRegex = normalizedValue
            "replaceRegex" -> contentRule.replaceRegex = normalizedValue
            "imageStyle" -> contentRule.imageStyle = normalizedValue
            "imageDecode" -> contentRule.imageDecode = normalizedValue
            "payAction" -> contentRule.payAction = normalizedValue
            "callBackJs" -> contentRule.callBackJs = normalizedValue
            "contentBatch" -> contentRule.contentBatch = normalizedValue
            "maxBatchSize" -> contentRule.maxBatchSize = normalizedValue?.toIntOrNull()
        }
    }
}

private fun applyReviewFields(
    reviewRule: ReviewRule,
    fields: List<BookSourceEditField>,
) {
    fields.forEach { field ->
        val normalizedValue = field.value.takeIf { value -> value.isNotBlank() }
        when (field.key) {
            "reviewSummaryUrl" -> reviewRule.reviewSummaryUrl = normalizedValue
            "summaryListRule" -> reviewRule.summaryListRule = normalizedValue
            "summaryParagraphIndexRule" -> reviewRule.summaryParagraphIndexRule = normalizedValue
            "summaryCountRule" -> reviewRule.summaryCountRule = normalizedValue
            "summaryParagraphDataRule" -> reviewRule.summaryParagraphDataRule = normalizedValue
            "reviewDetailUrl" -> reviewRule.reviewDetailUrl = normalizedValue
            "reviewDetailNextPageUrl" -> reviewRule.reviewDetailNextPageUrl = normalizedValue
            "detailListRule" -> reviewRule.detailListRule = normalizedValue
            "detailIdRule" -> reviewRule.detailIdRule = normalizedValue
            "detailAvatarRule" -> reviewRule.detailAvatarRule = normalizedValue
            "detailNameRule" -> reviewRule.detailNameRule = normalizedValue
            "detailBadgeRule" -> reviewRule.detailBadgeRule = normalizedValue
            "detailContentRule" -> reviewRule.detailContentRule = normalizedValue
            "reviewQuoteUrl" -> reviewRule.reviewQuoteUrl = normalizedValue
            "replyListRule" -> reviewRule.replyListRule = normalizedValue
            "replyIdRule" -> reviewRule.replyIdRule = normalizedValue
            "replyAvatarRule" -> reviewRule.replyAvatarRule = normalizedValue
            "replyNameRule" -> reviewRule.replyNameRule = normalizedValue
            "replyBadgeRule" -> reviewRule.replyBadgeRule = normalizedValue
            "replyContentRule" -> reviewRule.replyContentRule = normalizedValue
        }
    }
}
