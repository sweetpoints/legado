package io.legado.app.model.sourceEngine

import org.jsoup.Connection

/** The existing JSON response contract shared by native Java HTTP and explicit org.jsoup. */
internal object LegacyJsoupResponseCodec {
    fun encode(response: Connection.Response): Map<String, Any?> =
        mapOf(
            "__legacyResponseKind" to "jsoup",
            "url" to response.url().toString(),
            "status" to response.statusCode(),
            "message" to response.statusMessage(),
            "body" to response.body(),
            "headers" to response.headers(),
            "multiHeaders" to response.multiHeaders(),
            "cookieMap" to response.cookies(),
            "bytes" to response.bodyAsBytes().map { it.toInt() },
        )
}
