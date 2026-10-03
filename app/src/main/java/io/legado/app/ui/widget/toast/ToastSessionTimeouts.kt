package io.legado.app.ui.widget.toast

import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Caps a Toast that never attaches and provides a visible-time fallback when callbacks are absent.
 */
internal class ToastSessionTimeouts(
    private val onTimeout: () -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var closed = false
    private val pendingAttachment = Runnable { expire() }
    private val visibleFallback = Runnable { expire() }

    fun startPendingAttachmentTimeout(timeoutMillis: Long = MAX_PENDING_ATTACHMENT_MILLIS) {
        handler.postDelayed(pendingAttachment, timeoutMillis)
    }

    fun onAttached(duration: Int) {
        if (closed) return
        handler.removeCallbacks(pendingAttachment)
        val visibleTimeout =
            if (duration == Toast.LENGTH_LONG) LONG_VISIBLE_FALLBACK_MILLIS
            else SHORT_VISIBLE_FALLBACK_MILLIS
        handler.postDelayed(visibleFallback, visibleTimeout)
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(pendingAttachment)
        handler.removeCallbacks(visibleFallback)
    }

    private fun expire() {
        if (closed) return
        close()
        onTimeout()
    }
}

private const val SHORT_VISIBLE_FALLBACK_MILLIS = 2_500L
private const val LONG_VISIBLE_FALLBACK_MILLIS = 4_000L
private const val MAX_PENDING_ATTACHMENT_MILLIS = 10_000L
