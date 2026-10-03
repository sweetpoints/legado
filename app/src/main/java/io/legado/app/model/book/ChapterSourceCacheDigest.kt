package io.legado.app.model.book

import java.security.MessageDigest

/** Missing and an existing empty cache are different optimistic-write baselines. */
internal object ChapterSourceCacheDigest {
    fun of(content: String?): String =
        if (content == null) "missing"
        else
            "sha256:" +
                MessageDigest.getInstance("SHA-256")
                    .digest(content.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
}
