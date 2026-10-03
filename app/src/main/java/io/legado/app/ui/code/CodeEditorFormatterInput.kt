package io.legado.app.ui.code

import io.legado.app.help.CacheManager
import java.util.UUID

internal interface CodeEditorFormatterInputCache {
    fun put(key: String, text: String)

    fun remove(key: String)
}

internal object MemoryCodeEditorFormatterInputCache : CodeEditorFormatterInputCache {
    override fun put(key: String, text: String) = CacheManager.putMemory(key, text)

    override fun remove(key: String) = CacheManager.deleteMemory(key)
}

/** Each suspended formatter owns its input key, including cancellation and overlapping editors. */
internal suspend fun <T> withCodeEditorFormatterInput(
    text: String,
    cache: CodeEditorFormatterInputCache = MemoryCodeEditorFormatterInputCache,
    format: suspend (String) -> T,
): T {
    val key = "code-format-${UUID.randomUUID()}"
    return try {
        cache.put(key, text)
        format(key)
    } finally {
        // Another owner can still be formatting when this one exits. Never clear a shared
        // key or broaden cleanup: only this operation's UUID belongs to this finally block.
        cache.remove(key)
    }
}
