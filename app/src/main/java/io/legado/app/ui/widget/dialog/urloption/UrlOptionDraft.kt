package io.legado.app.ui.widget.dialog.urloption

import androidx.compose.runtime.saveable.listSaver
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON

/** UI-only draft. Request parsing remains owned by AnalyzeUrl's existing setters. */
data class UrlOptionDraft(val values: List<String> = List(UrlOptionField.entries.size) { "" }, val webView: Boolean = false) {
    operator fun get(field: UrlOptionField): String = values[field.ordinal]
    fun update(field: UrlOptionField, value: String) = copy(values = values.mapIndexed { index, old -> if (index == field.ordinal) value else old })
    fun toJson(): String = GSON.toJson(AnalyzeUrl.UrlOption().apply {
        useWebView(webView)
        setMethod(get(UrlOptionField.Method)); setCharset(get(UrlOptionField.Charset))
        setHeaders(get(UrlOptionField.Headers)); setBody(get(UrlOptionField.Body))
        setType(get(UrlOptionField.Type)); setRetry(get(UrlOptionField.Retry))
        setWebJs(get(UrlOptionField.WebJs)); setJs(get(UrlOptionField.Js))
        setBodyJs(get(UrlOptionField.BodyJs)); setDnsIp(get(UrlOptionField.DnsIp))
    })
    companion object {
        val Saver = listSaver<UrlOptionDraft, Any>(
            save = { listOf(it.webView) + it.values },
            restore = { UrlOptionDraft(it.drop(1).map { value -> value as String }, it[0] as Boolean) }
        )
    }
}

enum class UrlOptionField(val label: String, val multiline: Boolean = false) {
    Method("method"), Charset("charset"), Headers("headers", true), Body("body", true),
    Type("type"), Retry("retry"), WebJs("webJs", true), Js("js", true), BodyJs("bodyJs", true), DnsIp("dnsIp")
}
