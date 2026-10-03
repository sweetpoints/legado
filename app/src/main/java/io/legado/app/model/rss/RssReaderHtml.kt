package io.legado.app.model.rss

import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL

/** The reader's established preload/style rules are independent of the Android host. */
fun rssReaderHtml(content: String, style: String?, preload: Boolean): String {
    val html = StringBuilder(content.length + JS_URL.length + 200)
    if (preload) {
        val head = content.indexOf("<head>")
        if (head >= 0) html.append(content, 0, head + 6).append(JS_URL).append(content, head + 6, content.length)
        else html.append("<head>").append(JS_URL).append("</head>").append(content)
    } else html.append(content)
    val styleEnd = html.indexOf("</style>")
    if (styleEnd >= 0) {
        if (!style.isNullOrBlank()) html.insert(styleEnd + 8, "<style>$style</style>")
        return html.toString()
    }
    val css = style.takeUnless { it.isNullOrBlank() } ?: "img{max-width:100% !important; width:auto; height:auto;}video{object-fit:fill; max-width:100% !important; width:auto; height:auto;}body{word-wrap:break-word; height:auto;max-width: 100%; width:auto;}"
    val headEnd = html.indexOf("</head>")
    html.insert(if (headEnd >= 0) headEnd else 0, "<style>$css</style>")
    return html.toString()
}
fun rssReaderStartHtml(content: String, javascript: String?, style: String?, preload: Boolean): String {
    val value = if (!javascript.isNullOrBlank()) {
        val bodyEnd = content.indexOf("</body>")
        if (bodyEnd >= 0) StringBuilder(content.length + javascript.length + 20)
            .append(content, 0, bodyEnd).append("<script>$javascript</script>").append(content, bodyEnd, content.length).toString()
        else "<body>$content<script>$javascript</script></body>"
    } else content
    return rssReaderHtml(value, style, preload)
}
