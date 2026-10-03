package io.legado.app.model.book

import io.legado.app.data.entities.SearchBook
import java.util.concurrent.atomic.AtomicBoolean

/** Compatibility helpers for callers that retain one-shot source results and success completion. */
internal class PendingEvent<out T>(private val value: T) {
    private val handled = AtomicBoolean(false)
    fun peek(): T? = value.takeIf { !handled.get() }
    fun take(): T? = value.takeIf { handled.compareAndSet(false, true) }
}
internal class SourceChangeCompletion(private val deleteAfterChange: SearchBook?, private val delete: (SearchBook) -> Unit) {
    private val completed = AtomicBoolean(false)
    fun success() {
        val source = deleteAfterChange ?: return
        if (completed.compareAndSet(false, true)) delete(source)
    }
}
