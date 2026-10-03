package io.legado.app.data.repository

import io.legado.app.data.entities.RssSource
import io.legado.app.help.RuleComplete

enum class RssSourceEditorField(val key: String, val tab: Int) {
    SourceName("sourceName", 0),
    SourceUrl("sourceUrl", 0),
    SourceIcon("sourceIcon", 0),
    SourceGroup("sourceGroup", 0),
    SourceComment("sourceComment", 0),
    SearchUrl("searchUrl", 0),
    SortUrl("sortUrl", 0),
    LoginUrl("loginUrl", 0),
    LoginUi("loginUi", 0),
    LoginCheckJs("loginCheckJs", 0),
    CoverDecodeJs("coverDecodeJs", 0),
    Header("header", 0),
    VariableComment("variableComment", 0),
    ConcurrentRate("concurrentRate", 0),
    JsLib("jsLib", 0),
    StartHtml("startHtml", 1),
    StartStyle("startStyle", 1),
    StartJs("startJs", 1),
    PreloadJs("preloadJs", 1),
    RuleArticles("ruleArticles", 2),
    RuleNextPage("ruleNextPage", 2),
    RuleTitle("ruleTitle", 2),
    RulePubDate("rulePubDate", 2),
    RuleDescription("ruleDescription", 2),
    RuleImage("ruleImage", 2),
    RuleLink("ruleLink", 2),
    RuleContent("ruleContent", 3),
    NextContentUrl("nextContentUrl", 3),
    Style("style", 3),
    InjectJs("injectJs", 3),
    ContentWhitelist("contentWhitelist", 3),
    ContentBlacklist("contentBlacklist", 3),
    ShouldOverrideUrlLoading("shouldOverrideUrlLoading", 3),
}

data class RssSourceEditorText(val text: String = "", val start: Int = 0, val end: Int = start) {
    fun bounded() = copy(start = start.coerceIn(0, text.length), end = end.coerceIn(0, text.length))
}

enum class RssSourceEditorSaveAction {
    Close,
    Debug,
    Login,
    Variable,
}

data class RssSourceEditorDraft(
    val fields: Map<RssSourceEditorField, RssSourceEditorText> =
        RssSourceEditorField.entries.associateWith { RssSourceEditorText() },
    val enabled: Boolean = true,
    val singleUrl: Boolean = false,
    val cookieJar: Boolean = true,
    val preload: Boolean = false,
    val enableJs: Boolean = true,
    val loadWithBaseUrl: Boolean = true,
    val showWebLog: Boolean = false,
    val cacheFirst: Boolean = false,
    val type: Int = 0,
    val articleStyle: Int = 0,
) {
    operator fun get(field: RssSourceEditorField) = fields[field] ?: RssSourceEditorText()

    fun with(field: RssSourceEditorField, value: RssSourceEditorText) =
        copy(fields = fields + (field to value.bounded()))

    fun valid() =
        get(RssSourceEditorField.SourceName).text.isNotBlank() &&
            get(RssSourceEditorField.SourceUrl).text.isNotBlank()

    /**
     * Empty nullable fields match the native editor; nonblank source text is preserved verbatim.
     */
    fun entity(base: RssSource = RssSource(), autoComplete: Boolean = false): RssSource {
        fun text(field: RssSourceEditorField) = get(field).text.takeIf { it.isNotBlank() }
        fun rule(
            field: RssSourceEditorField,
            preRule: String? = text(RssSourceEditorField.RuleArticles),
            type: Int = 1,
        ): String? =
            if (autoComplete) RuleComplete.autoComplete(text(field), preRule, type) else text(field)
        return base.copy(
            sourceName = text(RssSourceEditorField.SourceName) ?: "",
            sourceUrl = text(RssSourceEditorField.SourceUrl) ?: "",
            sourceIcon = text(RssSourceEditorField.SourceIcon) ?: "",
            sourceGroup = text(RssSourceEditorField.SourceGroup),
            sourceComment = text(RssSourceEditorField.SourceComment),
            searchUrl = text(RssSourceEditorField.SearchUrl),
            sortUrl = text(RssSourceEditorField.SortUrl),
            loginUrl = text(RssSourceEditorField.LoginUrl),
            loginUi = text(RssSourceEditorField.LoginUi),
            loginCheckJs = text(RssSourceEditorField.LoginCheckJs),
            coverDecodeJs = text(RssSourceEditorField.CoverDecodeJs),
            header = text(RssSourceEditorField.Header),
            variableComment = text(RssSourceEditorField.VariableComment),
            concurrentRate = text(RssSourceEditorField.ConcurrentRate),
            jsLib = text(RssSourceEditorField.JsLib),
            startHtml = text(RssSourceEditorField.StartHtml),
            startStyle = text(RssSourceEditorField.StartStyle),
            startJs = text(RssSourceEditorField.StartJs),
            preloadJs = text(RssSourceEditorField.PreloadJs),
            ruleArticles = text(RssSourceEditorField.RuleArticles),
            ruleNextPage = rule(RssSourceEditorField.RuleNextPage, type = 2),
            ruleTitle = rule(RssSourceEditorField.RuleTitle),
            rulePubDate = rule(RssSourceEditorField.RulePubDate),
            ruleDescription = rule(RssSourceEditorField.RuleDescription),
            ruleImage = rule(RssSourceEditorField.RuleImage, type = 3),
            ruleLink = rule(RssSourceEditorField.RuleLink),
            ruleContent = rule(RssSourceEditorField.RuleContent),
            nextContentUrl = rule(RssSourceEditorField.NextContentUrl, preRule = null, type = 2),
            style = text(RssSourceEditorField.Style),
            injectJs = text(RssSourceEditorField.InjectJs),
            contentWhitelist = text(RssSourceEditorField.ContentWhitelist),
            contentBlacklist = text(RssSourceEditorField.ContentBlacklist),
            shouldOverrideUrlLoading = text(RssSourceEditorField.ShouldOverrideUrlLoading),
            enabled = enabled,
            singleUrl = singleUrl,
            enabledCookieJar = cookieJar,
            preload = preload,
            enableJs = enableJs,
            loadWithBaseUrl = loadWithBaseUrl,
            showWebLog = showWebLog,
            cacheFirst = cacheFirst,
            type = type.takeIf { it in 0..2 } ?: 0,
            articleStyle = articleStyle.takeIf { it in 0..4 } ?: 0,
        )
    }

    fun sameContent(other: RssSourceEditorDraft): Boolean =
        entity().let { current ->
            val previous = other.entity()
            val currentFields = from(current).fields
            val previousFields = from(previous).fields
            // RssSource.equals compares only URL. Compare every edited field instead.
            RssSourceEditorField.entries.all {
                currentFields.getValue(it).text == previousFields.getValue(it).text
            } &&
                copy(
                    fields = emptyMap(),
                    type = current.type,
                    articleStyle = current.articleStyle,
                ) ==
                    other.copy(
                        fields = emptyMap(),
                        type = previous.type,
                        articleStyle = previous.articleStyle,
                    )
        }

    companion object {
        fun from(source: RssSource) =
            RssSourceEditorDraft(
                fields =
                    mapOf(
                        RssSourceEditorField.SourceName to
                            RssSourceEditorText(source.sourceName.orEmpty()),
                        RssSourceEditorField.SourceUrl to
                            RssSourceEditorText(source.sourceUrl.orEmpty()),
                        RssSourceEditorField.SourceIcon to
                            RssSourceEditorText(source.sourceIcon.orEmpty()),
                        RssSourceEditorField.SourceGroup to
                            RssSourceEditorText(source.sourceGroup.orEmpty()),
                        RssSourceEditorField.SourceComment to
                            RssSourceEditorText(source.sourceComment.orEmpty()),
                        RssSourceEditorField.SearchUrl to
                            RssSourceEditorText(source.searchUrl.orEmpty()),
                        RssSourceEditorField.SortUrl to
                            RssSourceEditorText(source.sortUrl.orEmpty()),
                        RssSourceEditorField.LoginUrl to
                            RssSourceEditorText(source.loginUrl.orEmpty()),
                        RssSourceEditorField.LoginUi to
                            RssSourceEditorText(source.loginUi.orEmpty()),
                        RssSourceEditorField.LoginCheckJs to
                            RssSourceEditorText(source.loginCheckJs.orEmpty()),
                        RssSourceEditorField.CoverDecodeJs to
                            RssSourceEditorText(source.coverDecodeJs.orEmpty()),
                        RssSourceEditorField.Header to RssSourceEditorText(source.header.orEmpty()),
                        RssSourceEditorField.VariableComment to
                            RssSourceEditorText(source.variableComment.orEmpty()),
                        RssSourceEditorField.ConcurrentRate to
                            RssSourceEditorText(source.concurrentRate.orEmpty()),
                        RssSourceEditorField.JsLib to RssSourceEditorText(source.jsLib.orEmpty()),
                        RssSourceEditorField.StartHtml to
                            RssSourceEditorText(source.startHtml.orEmpty()),
                        RssSourceEditorField.StartStyle to
                            RssSourceEditorText(source.startStyle.orEmpty()),
                        RssSourceEditorField.StartJs to
                            RssSourceEditorText(source.startJs.orEmpty()),
                        RssSourceEditorField.PreloadJs to
                            RssSourceEditorText(source.preloadJs.orEmpty()),
                        RssSourceEditorField.RuleArticles to
                            RssSourceEditorText(source.ruleArticles.orEmpty()),
                        RssSourceEditorField.RuleNextPage to
                            RssSourceEditorText(source.ruleNextPage.orEmpty()),
                        RssSourceEditorField.RuleTitle to
                            RssSourceEditorText(source.ruleTitle.orEmpty()),
                        RssSourceEditorField.RulePubDate to
                            RssSourceEditorText(source.rulePubDate.orEmpty()),
                        RssSourceEditorField.RuleDescription to
                            RssSourceEditorText(source.ruleDescription.orEmpty()),
                        RssSourceEditorField.RuleImage to
                            RssSourceEditorText(source.ruleImage.orEmpty()),
                        RssSourceEditorField.RuleLink to
                            RssSourceEditorText(source.ruleLink.orEmpty()),
                        RssSourceEditorField.RuleContent to
                            RssSourceEditorText(source.ruleContent.orEmpty()),
                        RssSourceEditorField.NextContentUrl to
                            RssSourceEditorText(source.nextContentUrl.orEmpty()),
                        RssSourceEditorField.Style to RssSourceEditorText(source.style.orEmpty()),
                        RssSourceEditorField.InjectJs to
                            RssSourceEditorText(source.injectJs.orEmpty()),
                        RssSourceEditorField.ContentWhitelist to
                            RssSourceEditorText(source.contentWhitelist.orEmpty()),
                        RssSourceEditorField.ContentBlacklist to
                            RssSourceEditorText(source.contentBlacklist.orEmpty()),
                        RssSourceEditorField.ShouldOverrideUrlLoading to
                            RssSourceEditorText(source.shouldOverrideUrlLoading.orEmpty()),
                    ),
                enabled = source.enabled,
                singleUrl = source.singleUrl,
                cookieJar = source.enabledCookieJar == true,
                preload = source.preload,
                enableJs = source.enableJs,
                loadWithBaseUrl = source.loadWithBaseUrl,
                showWebLog = source.showWebLog,
                cacheFirst = source.cacheFirst,
                type = source.type.takeIf { it in 0..2 } ?: 0,
                articleStyle = source.articleStyle.takeIf { it in 0..4 } ?: 0,
            )
    }
}

data class RssSourceEditorDelivery(
    val token: String,
    val action: RssSourceEditorSaveAction,
    val sourceUrl: String,
    val loginAvailable: Boolean,
)

data class RssSourceEditorDocument(
    val originalKey: String?,
    val draft: RssSourceEditorDraft,
    val baseline: RssSourceEditorDraft = draft,
    val lastUpdateTime: Long = 0,
    val customOrder: Int = 0,
    val revision: Long = 0,
    val delivery: RssSourceEditorDelivery? = null,
)
