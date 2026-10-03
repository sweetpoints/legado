package io.legado.app.model.rss

import io.legado.app.utils.textArray
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup

/** Decode evaluateJavascript's JSON string before projecting the visible HTML text for TTS. */
fun rssReaderSpeechText(encoded: String): String {
    val html = StringEscapeUtils.unescapeJson(encoded).replace("^\"|\"$".toRegex(), "")
    return Jsoup.parse(html).textArray().joinToString("\n")
}
