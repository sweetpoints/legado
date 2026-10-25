package io.legado.app.ui.book.bookmark

import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The persisted native ticket owns every draft, Room write and completion. */
internal class BookmarkEditorNativeCoordinator(
    private val model: BookmarkEditorViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val dismiss: () -> Unit,
) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private var owner: Any? = null
    private var closed = false
    private var dismissed = false
    private var revision = 0L
    private var last: Map<String, Any?>? = null
    private val rejected = mutableSetOf<String>()
    private val hostReady = MutableStateFlow(0L)
    val changes = model.state.map { projection() }
    private val delivery = scope.launch {
        combine(model.state, lifecycle.currentStateFlow, hostReady) { state, _, _ -> state }.collect { state ->
            if (!closed && !dismissed && state.finished && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()) {
                dismissed = true
                dismiss()
            }
        }
    }
    fun onHostReady() { hostReady.value++ }
    fun requestClose() { if (!closed) model.cancel() }
    fun bind(): Any = Any().also { check(!closed); owner = it }
    fun detach(binding: Any) { if (owner === binding) owner = null }
    private fun valid(binding: Any) { check(!closed && owner === binding) { "Retired bookmark editor" } }
    private fun projection(): Map<String, Any?> = model.state.value.let {
        mapOf("generation" to generation, "loading" to it.loading, "saving" to it.busy,
            "finished" to it.finished,
            "loadFailed" to (!it.loaded && !it.loading && it.error != null),
            "canEdit" to it.canEdit, "error" to it.error, "focus" to it.focused,
            "chapter" to it.chapter, "canDelete" to it.canDelete,
            "text" to it.bookText, "textStart" to it.textStart, "textEnd" to it.textEnd,
            "content" to it.content, "contentStart" to it.contentStart, "contentEnd" to it.contentEnd)
    }
    fun snapshot(binding: Any): Map<String, Any?> {
        valid(binding)
        val next = projection()
        if (next != last) { revision++; last = next }
        return next + ("revision" to revision)
    }
    fun command(binding: Any, arguments: Any?): Map<String, Any?> {
        valid(binding)
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val args = arguments as? Map<*, *> ?: error("Invalid command")
        require(args["generation"] == generation)
        val op = args["op"] as? String ?: error("Missing operation")
        if (op == "field") {
            val key = args["key"] as? String ?: error("Missing field")
            require(key in setOf("text", "content"))
            try {
                check(model.state.value.canEdit)
                val text = args["text"] as String
                val start = args["start"] as Int; val end = args["end"] as Int
                require(start in 0..text.length && end in 0..text.length)
                if (key == "text") model.bookText(text, start, end) else model.content(text, start, end)
                rejected.remove(key)
            } catch (failure: Exception) { rejected.add(key); throw failure }
        } else {
            if (op !in setOf("cancel")) check(rejected.isEmpty()) { "Unacknowledged field" }
            when (op) {
                "focus" -> { check(model.state.value.canEdit); model.focus(args["key"] as String) }
                "confirm", "delete" -> {
                    check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready() && model.state.value.canEdit)
                    if (op == "delete") { check(model.state.value.canDelete); model.delete() } else model.confirm()
                }
                "retry" -> model.load()
                "cancel" -> model.cancel()
                else -> error("Unknown bookmark operation")
            }
        }
        return snapshot(binding)
    }
    override fun close() { closed = true; owner = null; delivery.cancel() }
    private companion object { val generations = AtomicInteger() }
}
