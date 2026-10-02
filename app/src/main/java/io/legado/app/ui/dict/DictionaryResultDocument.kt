package io.legado.app.ui.dict

import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import java.util.UUID

internal const val DICTIONARY_DOCUMENT_BASE = "https://dictionary-content.invalid/"
data class DictionaryResultAction(val name: String, val script: String)
data class DictionaryResultDocument(val body: String, val text: String,
    val actions: Map<String, DictionaryResultAction>, val images: Map<String, String>) {
    fun action(url: String): DictionaryResultAction? = actions[url]
    fun image(url: String): String? = images[url]
}
/** Content compilation has no UI/context dependency and runs off the main thread. */
fun dictionaryResultDocument(content: String): DictionaryResultDocument {
    val trimmed = content.trimStart()
    val markdown = trimmed.startsWith("<md>")
    val html = if (markdown) {
        val end = trimmed.lastIndexOf('<')
        if (end < 4) return DictionaryResultDocument(Jsoup.parseBodyFragment("").body().appendElement("pre").text(trimmed).outerHtml(), trimmed, emptyMap(), emptyMap())
        val extensions = listOf(TablesExtension.create())
        HtmlRenderer.builder().extensions(extensions).build().render(Parser.builder().extensions(extensions).build().parse(trimmed.substring(4, end)))
    } else content
    val document = Jsoup.parseBodyFragment(html)
    document.select("script, iframe, object, embed, link, meta, input, select").remove()
    document.select("form").forEach { it.unwrap() }
    document.allElements.forEach { element ->
        element.attributes().asList().filter { it.key.startsWith("on", ignoreCase = true) }.forEach { element.removeAttr(it.key) }
    }
    val actions = linkedMapOf<String, DictionaryResultAction>()
    val images = linkedMapOf<String, String>()
    val token = UUID.randomUUID().toString()
    fun actionUrl(name: String, script: String): String {
        val url = "https://dictionary-action.invalid/$token/${actions.size}"
        actions[url] = DictionaryResultAction(name, script)
        return url
    }
    document.select("button").forEach { button ->
        val parts = button.wholeText().split("@onclick:", limit = 2)
        if (!markdown && parts.size == 2) {
            button.tagName("a").attr("href", actionUrl("button ${parts[0]}", parts[1])).attr("class", "dictionary-button").text(parts[0])
        } else button.tagName("span")
    }
    document.select("img[src]").forEach { image ->
        val source = image.attr("src")
        val matcher = AnalyzeUrl.paramPattern.matcher(source)
        val options = if (matcher.find()) GSON.fromJsonObject<Map<String, String>>(source.substring(matcher.end())).getOrNull().orEmpty() else emptyMap()
        val url = "https://dictionary-image.invalid/$token/${images.size}"
        images[url] = source
        image.attr("src", url)
        val width = options["width"]?.let { if (it.endsWith('%')) it.dropLast(1).toIntOrNull()?.let { value -> "${value.coerceAtLeast(1)}%" } else it.toIntOrNull()?.takeIf { it > 0 }?.let { value -> "${value}px" } }
        val align = when (options["style"]) { "center" -> "margin-left:auto;margin-right:auto;display:block;"; "right" -> "margin-left:auto;display:block;"; else -> "" }
        image.attr("style", "max-width:100%;height:auto;" + (width?.let { "width:$it;" } ?: "") + align)
        if (!markdown && !options["click"].isNullOrBlank()) {
            image.wrap("<a href='${actionUrl("image", checkNotNull(options["click"]))}'></a>")
        }
    }
    return DictionaryResultDocument(document.body().html(), document.body().text(), actions.toMap(), images.toMap())
}
