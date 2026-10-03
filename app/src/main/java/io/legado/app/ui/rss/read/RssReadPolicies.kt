package io.legado.app.ui.rss.read

import io.legado.app.data.entities.RssArticle

internal fun resolveRssReadTitle(
    intentTitle: String?,
    sourceName: String?,
    origin: String,
): String = intentTitle ?: sourceName ?: origin

internal sealed interface RssReadLoadTarget {
    data class CachedContent(val content: String) : RssReadLoadTarget

    data class RuleContent(val article: RssArticle, val rule: String) : RssReadLoadTarget

    data class Url(val url: String, val baseUrl: String) : RssReadLoadTarget
}

internal fun resolveRssReadLoadTarget(
    article: RssArticle?,
    historyLink: String,
    historyOrigin: String,
    ruleContent: String?,
): RssReadLoadTarget {
    val description = article?.description
    return when {
        article == null -> RssReadLoadTarget.Url(historyLink, historyOrigin)
        !description.isNullOrBlank() -> RssReadLoadTarget.CachedContent(description)
        !ruleContent.isNullOrBlank() -> RssReadLoadTarget.RuleContent(article, ruleContent)
        else -> RssReadLoadTarget.Url(article.link, article.origin)
    }
}
