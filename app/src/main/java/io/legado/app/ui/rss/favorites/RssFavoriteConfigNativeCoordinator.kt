package io.legado.app.ui.rss.favorites

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.RssFavoriteConfigAction
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map

/** The existing private draft and host callbacks remain native; Flutter owns presentation only. */
internal class RssFavoriteConfigNativeCoordinator(
    private val model: RssFavoriteConfigViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val canDeliver: () -> Boolean,
    private val update: (String?, String?) -> Unit,
    private val delete: () -> Unit,
    private val dismiss: () -> Unit,
    private val cancelable: (Boolean) -> Unit,
) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private var owner: Any? = null
    private var closed = false
    private var dismissed = false
    private var revision = 0L
    private var last: Map<String, Any?>? = null
    private val rejected = mutableSetOf<String>()
    val changes = model.state.map { snapshotProjection() }
    private val delivery = scope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { state ->
                cancelable(!state.busy)
                if (closed || !canDeliver()) return@collect
                if (!state.finished) model.consume()?.let { effect ->
                    if (effect.action == RssFavoriteConfigAction.Save) update(effect.title, effect.group)
                    else delete()
                }
                if (model.state.value.finished && !dismissed) {
                    dismissed = true
                    dismiss()
                }
            }
        }
    }
    fun bind(): Any = Any().also { check(!closed); owner = it }
    fun detach(binding: Any) { if (owner === binding) owner = null }
    private fun valid(binding: Any) = check(!closed && owner === binding) { "Retired config binding" }
    private fun snapshotProjection(): Map<String, Any?> = model.state.value.let {
        mapOf("generation" to generation, "loaded" to it.loaded, "loading" to it.loading,
            "busy" to it.busy, "canEdit" to it.canEdit, "error" to (it.error != null),
            "finished" to it.finished, "title" to it.title, "group" to it.group,
            "titleStart" to it.titleStart, "titleEnd" to it.titleEnd,
            "groupStart" to it.groupStart, "groupEnd" to it.groupEnd, "focus" to it.focus)
    }
    fun snapshot(binding: Any): Map<String, Any?> {
        valid(binding)
        val value = snapshotProjection()
        if (last != value) { revision++; last = value }
        return value + ("revision" to revision)
    }
    fun command(binding: Any, arguments: Any?): Map<String, Any?> {
        valid(binding)
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val args = arguments as? Map<*, *> ?: error("Invalid config command")
        require(args["generation"] == generation)
        val op = args["op"] as? String ?: error("Missing operation")
        if (op == "field") {
            val key = args["key"] as? String ?: error("Missing field")
            require(key == "title" || key == "group")
            try {
                check(model.state.value.canEdit)
                val text = args["text"] as? String ?: error("Missing text")
                val start = args["start"] as? Int ?: error("Missing selection")
                val end = args["end"] as? Int ?: error("Missing selection")
                require(start in 0..text.length && end in 0..text.length)
                if (key == "title") model.title(text, start, end) else model.group(text, start, end)
                model.focus(key)
                rejected.remove(key)
            } catch (failure: Exception) { rejected.add(key); throw failure }
        } else {
            if (op != "cancel") check(rejected.isEmpty()) { "Unacknowledged field" }
            when (op) {
                "focus" -> { check(model.state.value.canEdit); model.focus(args["key"] as? String ?: "") }
                "save" -> { check(model.state.value.canEdit); model.confirm() }
                "delete" -> { check(model.state.value.canEdit); model.delete() }
                "cancel" -> { check(!model.state.value.busy); model.cancel() }
                "retry" -> model.load()
                else -> error("Unknown config operation")
            }
        }
        return snapshot(binding)
    }
    override fun close() { closed = true; owner = null; delivery.cancel() }
    private companion object { val generations = AtomicInteger() }
}
