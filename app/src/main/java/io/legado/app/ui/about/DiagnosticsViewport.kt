package io.legado.app.ui.about

import java.security.MessageDigest

internal data class DiagnosticsViewportCheckpoint(val page: Int, val index: Int, val offset: Double, val anchor: String?)
internal data class DiagnosticsViewportProjection(val page: Int, val pages: Int, val checkpoint: DiagnosticsViewportCheckpoint)

/** Native-only viewport receipt. The anchor is a digest, never a file path or log body. */
internal object DiagnosticsViewport {
    fun move(requestedPage: Int, rowCount: Int, delta: Int): Int {
        require(rowCount >= 0 && delta in setOf(-1, 1))
        val lastPage = maxOf(0, (rowCount - 1) / 100)
        return (requestedPage.coerceIn(0, lastPage) + delta).coerceIn(0, lastPage)
    }

    fun anchor(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun project(ids: List<String>, checkpoint: DiagnosticsViewportCheckpoint, settled: Boolean): DiagnosticsViewportProjection {
        val pages = maxOf(1, (ids.size + 99) / 100)
        val displayPage = checkpoint.page.coerceIn(0, pages - 1)
        if (!settled) return DiagnosticsViewportProjection(displayPage, pages, checkpoint)
        val anchorIndex = checkpoint.anchor?.let { wanted -> ids.indexOfFirst { anchor(it) == wanted } } ?: -1
        val restored = if (anchorIndex >= 0) {
            checkpoint.copy(page = anchorIndex / 100, index = anchorIndex % 100)
        } else {
            val count = (ids.size - displayPage * 100).coerceIn(0, 100)
            checkpoint.copy(page = displayPage, index = checkpoint.index.coerceIn(0, maxOf(0, count - 1)),
                offset = if (count == 0) 0.0 else checkpoint.offset, anchor = null)
        }
        return DiagnosticsViewportProjection(restored.page, pages, restored)
    }
}
