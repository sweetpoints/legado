package io.legado.app.model.rss

import io.legado.app.data.repository.RssReaderRequest
import java.security.MessageDigest

/**
 * Call on IO: inline HTML can be several megabytes. Only this fixed-size result is an owner token.
 */
fun rssReaderImageOwner(request: RssReaderRequest): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(
            request.origin,
            request.title,
            request.link,
            request.sort,
            request.openUrl,
            request.startHtml,
        )
        .forEach { value ->
            digest.update(if (value == null) 0.toByte() else 1.toByte())
            if (value != null) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
