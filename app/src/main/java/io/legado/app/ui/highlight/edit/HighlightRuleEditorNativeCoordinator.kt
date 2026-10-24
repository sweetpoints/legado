package io.legado.app.ui.highlight.edit

import androidx.lifecycle.Lifecycle
import io.legado.app.help.HighlightStyle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The Flutter view owns only acknowledged UI input, never the Room/session/style result. */
internal class HighlightRuleEditorNativeCoordinator(
    private val model: HighlightRuleEditorViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val labels: Map<String, String>,
    private val preview: (HighlightStyle) -> Map<String, Any?>,
    private val styleChanged: () -> Unit,
    private val event: (HighlightRuleEditorEvent) -> Unit,
    private val color: (HighlightRuleColorDraft) -> Unit,
    private val deliveryFailed: (Exception) -> Unit = {},
) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private var owner: Any? = null
    private var closed = false
    private var revision = 0L
    private var previous: Map<String, Any?>? = null
    private var previousStyle: HighlightStyle? = null
    private var colorRequested: HighlightRuleColorDraft? = null
    private val rejected = mutableSetOf<String>()
    private val updates = MutableStateFlow(0L)
    val changes: Flow<Any> = merge(model.state.map { it as Any }, updates.map { it as Any })
    private val observer = scope.launch {
        combine(model.state, lifecycle.currentStateFlow, updates) { state, _, _ -> state }
            .collect { state ->
                if (!canDeliver() || state.loading) return@collect
                state.draft?.rule?.style?.let { style ->
                    if (style != previousStyle) {
                        previousStyle = style
                        try { styleChanged() } catch (error: Exception) { runCatching { deliveryFailed(error) } }
                    }
                }
                state.event?.let { value ->
                    model.consume(value)
                    try {
                        event(value)
                    } catch (error: Exception) {
                        // Saved is already consumed and durable. Never replay its side effect;
                        // close the finished host even if refreshing its reader failed.
                        runCatching { deliveryFailed(error) }
                        if (value == HighlightRuleEditorEvent.Saved) runCatching { event(HighlightRuleEditorEvent.Close) }
                    }
                }
                if (state.color == null) colorRequested = null
                state.color?.takeUnless { state.finished }?.let { request ->
                    if (colorRequested != request) { colorRequested = request; color(request) }
                }
            }
    }
    private fun canDeliver() = !closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()
    fun onHostReady() { updates.value++ }
    fun cancel() { if (!closed) model.cancel() }
    fun bind(): Any = Any().also { check(!closed); owner = it }
    fun detach(binding: Any) { if (owner === binding) owner = null }
    private fun checkOwner(binding: Any) { check(!closed && owner === binding) }
    fun snapshot(binding: Any): Map<String, Any?> {
        checkOwner(binding)
        val state = model.state.value
        val rule = state.draft?.rule
        val texts = mapOf("name" to rule?.name.orEmpty(), "group" to rule?.group.orEmpty(),
            "pattern" to rule?.pattern.orEmpty(), "scope" to rule?.scope.orEmpty())
        val fields = texts.mapValues { (key, text) ->
            val selection = state.selections[key] ?: (0 to 0)
            mapOf("text" to text, "start" to selection.first.coerceIn(0, text.length), "end" to selection.second.coerceIn(0, text.length))
        }
        val projection = mapOf<String, Any?>("labels" to labels, "fields" to fields,
            "focus" to state.focus, "scroll" to state.scroll, "loading" to state.loading, "saving" to state.saving,
            "finished" to state.finished, "editable" to (rule != null && !state.loading && !state.saving && !state.finished),
            "error" to state.error, "regex" to (rule?.isRegex == true), "body" to (rule?.applyToBody == true),
            "title" to (rule?.applyToTitle == true), "preview" to preview(rule?.style ?: HighlightStyle()))
        if (previous != projection) { previous = projection; revision++ }
        return projection + mapOf("generation" to generation, "revision" to revision)
    }
    fun command(binding: Any, arguments: Any?): Map<String, Any?> {
        checkOwner(binding)
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val args = arguments as? Map<*, *> ?: error("Invalid command")
        require(args["generation"] == generation)
        val op = args["op"] as? String ?: error("Missing operation")
        if (op == "field") {
            val key = args["key"] as? String ?: error("Missing field")
            try {
                model.field(key, args["text"] as String, args["start"] as Int, args["end"] as Int)
                rejected.remove(key)
            } catch (error: Exception) { rejected.add(key); throw error }
        } else {
            if (op != "cancel") check(rejected.isEmpty())
            when (op) {
                "focus" -> model.focus(args["key"] as String)
                "scroll" -> model.scroll(args["offset"] as Int)
                "flags" -> {
                    check(!model.state.value.loading && !model.state.value.saving && !model.state.value.finished)
                    model.change { it.copy(isRegex = args["regex"] as Boolean, applyToBody = args["body"] as Boolean, applyToTitle = args["title"] as Boolean) }
                }
                "style" -> { check(canDeliver()); model.openStyle() }
                "save" -> { check(canDeliver()); model.save() }
                "retry" -> model.retry()
                "cancel" -> model.cancel()
                else -> error("Unknown operation")
            }
        }
        return snapshot(binding)
    }
    override fun close() { closed = true; owner = null; observer.cancel() }
    private companion object { val generations = AtomicInteger() }
}
