package io.legado.app.ui.book.toc.rule

import io.legado.app.data.repository.TxtTocRuleSnapshot

import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Native ViewModel owns Room writes, draft state and fullscreen ActivityResult correlation. */
internal class TxtTocRuleEditorNativeCoordinator(
    private val model: TxtTocRuleEditorViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val canDeliver: () -> Boolean,
    private val fullEdit: (TxtTocEditorCodeRequest) -> Unit,
    private val copy: (String) -> Unit,
    private val paste: () -> String?,
    private val noFocus: () -> Unit,
    private val onSaved: (TxtTocRuleSnapshot) -> Unit,
    private val onCallbackFailure: (Exception) -> Unit,
    private val dismiss: () -> Unit,
) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private var owner: Any? = null
    private var closed = false
    private var dismissed = false
    private var revision = 0L
    private var last: Map<String, Any?>? = null
    private val rejected = mutableSetOf<String>()
    val changes = model.state.map { projection() }
    private val hostReady = MutableStateFlow(0L)
    private val delivery = scope.launch {
        combine(model.state, lifecycle.currentStateFlow, hostReady) { state, _, _ -> state }.collect { state ->
            if (!closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canDeliver() && state.finished && !dismissed) {
                // Consume before calling the host: a recreation or throwing callback must not deliver twice.
                dismissed = true
                val rule = model.consumeSavedRule()
                try {
                    try { rule?.let(onSaved) }
                    catch (failure: Exception) { onCallbackFailure(failure) }
                } finally { dismiss() }
            }
        }
    }
    fun onHostReady() { hostReady.value++ }
    fun requestClose() {
        if (model.state.value.confirmExit) model.keepEditing()
        else model.requestClose(rejected.isNotEmpty())
    }
    fun bind(): Any = Any().also { check(!closed); owner = it }
    fun detach(binding: Any) { if (owner === binding) owner = null }
    private fun valid(binding: Any) = check(!closed && owner === binding) { "Retired TXT TOC editor" }
    private fun editable() = model.state.value.let { !it.loading && !it.loadFailed && !it.saving && !it.finished && !it.confirmExit && !it.codeEditing }
    private fun projection(): Map<String, Any?> = model.state.value.let { state ->
        buildMap {
            put("generation", generation)
            put("loading", state.loading); put("saving", state.saving)
            put("finished", state.finished); put("confirmExit", state.confirmExit)
            put("loadFailed", state.loadFailed); put("error", state.error)
            put("canEdit", editable()); put("focus", state.focused?.name)
            TxtTocEditorField.entries.forEach { field ->
                put(field.name, state[field])
                put(field.name + "Start", state.cursors[field.ordinal])
                put(field.name + "End", state.selectionEnds[field.ordinal])
            }
        }
    }
    fun snapshot(binding: Any): Map<String, Any?> {
        valid(binding)
        val value = projection()
        if (last != value) { revision++; last = value }
        return value + ("revision" to revision)
    }
    fun command(binding: Any, arguments: Any?): Map<String, Any?> {
        valid(binding)
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val args = arguments as? Map<*, *> ?: error("Invalid editor command")
        require(args["generation"] == generation)
        val op = args["op"] as? String ?: error("Missing operation")
        if (op == "field") {
            val key = args["key"] as? String ?: error("Missing field")
            val field = TxtTocEditorField.entries.find { it.name == key } ?: error("Unknown field")
            try {
                check(editable())
                val text = args["text"] as? String ?: error("Missing text")
                val start = args["start"] as? Int ?: error("Missing selection")
                val end = args["end"] as? Int ?: error("Missing selection")
                require(start in 0..text.length && end in 0..text.length)
                model.setInput(field, text, start, end)
                model.focus(field)
                rejected.remove(key)
            } catch (failure: Exception) { rejected.add(key); throw failure }
        } else {
            // A failed local edit may only be abandoned via an explicit dirty-close path.
            if (op !in setOf("cancel", "keep", "discard")) check(rejected.isEmpty()) { "Unacknowledged field" }
            when (op) {
                "focus" -> { check(editable()); model.focus(TxtTocEditorField.valueOf(args["key"] as String)) }
                "save" -> { check(editable()); model.save() }
                "retry" -> model.load()
                "cancel" -> { check(!model.state.value.saving); model.requestClose(rejected.isNotEmpty() || args["localDirty"] == true) }
                "keep" -> { check(model.state.value.confirmExit); model.keepEditing() }
                "discard" -> { check(model.state.value.confirmExit); model.discard() }
                "copy", "paste", "fullscreen" -> {
                    check(editable())
                    check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canDeliver())
                    when (op) {
                        "copy" -> copy(model.copyJson())
                        "paste" -> model.paste(paste())
                        else -> {
                            val request = model.codeRequest()
                            if (request == null) noFocus()
                            else try { fullEdit(request) } catch (failure: Exception) {
                                model.codeCancelled(); throw failure
                            }
                        }
                    }
                }
                else -> error("Unknown editor operation")
            }
        }
        return snapshot(binding)
    }
    override fun close() { closed = true; owner = null; delivery.cancel() }
    private companion object { val generations = AtomicInteger() }
}
