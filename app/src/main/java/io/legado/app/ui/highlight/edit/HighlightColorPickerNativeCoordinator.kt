package io.legado.app.ui.highlight.edit

import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class HighlightColorPickerNativeCoordinator(private val model: HighlightColorPickerViewModel,
    private val lifecycle: Lifecycle, scope: CoroutineScope, private val ready: () -> Boolean,
    private val labels: Map<String, String>, private val completed: (Boolean, Int) -> Unit) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private var owner: Any? = null
    private var closed = false
    private var sequence = 0L
    private val updates = MutableStateFlow(0L)
    val changes: Flow<Any> = merge(model.state.map { it as Any }, updates.map { it as Any })
    private val observation = scope.launch {
        combine(model.state, lifecycle.currentStateFlow, updates) { state, _, _ -> state }.collect {
            if (!closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready())
                model.consumeResult()?.let { (save, value) -> completed(save, value) }
        }
    }
    fun onHostReady() { updates.value++ }
    fun cancel() { if (!closed) model.finish(false) }
    fun bind(): Any = Any().also { check(!closed); owner = it; sequence = 0 }
    fun detach(binding: Any) { if (owner === binding) owner = null }
    private fun checkOwner(binding: Any) { check(!closed && owner === binding) }
    fun snapshot(binding: Any): Map<String, Any?> {
        checkOwner(binding); val state = model.state.value
        return mapOf("generation" to generation, "nonce" to model.nonce, "revision" to state.revision,
            "sequence" to sequence, "value" to state.value, "hex" to state.hex, "invalid" to state.invalid,
            "finished" to state.finished, "saved" to state.saved, "withAlpha" to model.withAlpha,
            "presets" to model.presets, "labels" to labels)
    }
    fun command(binding: Any, args: Any?): Map<String, Any?> {
        checkOwner(binding); check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready())
        val value = args as? Map<*, *> ?: error("Invalid command")
        require(value["generation"] == generation && value["nonce"] == model.nonce)
        val next = (value["sequence"] as? Number)?.toLong() ?: error("Missing sequence")
        require(next > sequence)
        sequence = next
        when (value["op"]) {
            "hex" -> model.hex(value["text"] as String)
            "preset" -> model.preset(value["index"] as Int)
            "channel" -> model.channel(value["channel"] as String, value["value"] as Int)
            "save" -> model.finish(true)
            "cancel" -> model.finish(false)
            else -> error("Unknown operation")
        }
        return snapshot(binding)
    }
    override fun close() { closed = true; owner = null; observation.cancel() }
    private companion object { val generations = AtomicInteger() }
}
