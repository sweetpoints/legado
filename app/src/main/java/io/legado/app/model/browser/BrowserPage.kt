package io.legado.app.model.browser

internal data class BrowserRequest(val url: String, val title: String = "", val sourceName: String = "",
    val sourceOrigin: String = "", val sourceType: Int = 0, val html: String? = null,
    val verificationEnabled: Boolean = false, val refetchAfterSuccess: Boolean = true, val verificationKey: String? = null)
internal data class BrowserSource(val type: Int, val json: String)
internal data class BrowserPage(val request: BrowserRequest, val baseUrl: String, val html: String?,
    val localHtml: Boolean, val headers: Map<String, String>, val userAgent: String, val source: BrowserSource?)
internal data class BrowserVerification(val html: String, val url: String)
internal enum class BrowserReceiptKind { Close, Verified, ImageSaved, ImageFailed }
internal data class BrowserReceipt(val id: String, val kind: BrowserReceiptKind, val verification: BrowserVerification? = null, val message: String? = null)
internal data class BrowserSession(val request: BrowserRequest, val page: BrowserPage? = null, val title: String = request.title,
    val image: String? = null, val receipt: BrowserReceipt? = null, val finished: Boolean = false, val revision: Long = 0)

/** Matches the old local HTML injection, including malformed/missing/empty head tags. */
internal fun injectBrowserScript(html: String, script: String): String {
    val head = html.indexOf("<head", ignoreCase = true)
    if (head >= 0) {
        val end = html.indexOf('>', startIndex = head)
        if (end >= 0) return StringBuilder(html).insert(end + 1, "<script>$script</script>").toString()
    }
    return "<head><script>$script</script></head>$html"
}
