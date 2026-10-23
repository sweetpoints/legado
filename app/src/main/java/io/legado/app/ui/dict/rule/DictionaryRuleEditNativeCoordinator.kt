package io.legado.app.ui.dict.rule

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map

/** Native ViewModel owns Room writes, draft state and fullscreen ActivityResult correlation. */
internal class DictionaryRuleEditNativeCoordinator(
    private val model: DictionaryRuleEditViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val canDeliver: () -> Boolean,
    private val fullEdit: (DictionaryFullEditRequest) -> Unit,
    private val copy: (String) -> Unit,
    private val paste: () -> String?,
    private val noFocus: () -> Unit,
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
    private val delivery = scope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { state ->
                if (!closed && canDeliver() && state.finished && !dismissed) {
                    dismissed = true
                    dismiss()
                }
            }
        }
    }
    fun requestClose() {
        if (model.state.value.confirmExit) model.keepEditing()
        else model.requestClose(rejected.isNotEmpty())
    }
    fun bind(): Any = Any().also { check(!closed); owner = it }
    fun detach(binding: Any) { if (owner === binding) owner = null }
    private fun valid(binding: Any) = check(!closed && owner === binding) { "Retired dictionary editor" }
    private fun editable() = model.state.value.let { !it.loading && !it.loadFailed && !it.saving && !it.finished && !it.confirmExit }
    private fun projection(): Map<String, Any?> = model.state.value.let { state ->
        buildMap {
            put("generation", generation)
            put("loading", state.loading); put("saving", state.saving)
            put("finished", state.finished); put("confirmExit", state.confirmExit)
            put("loadFailed", state.loadFailed); put("error", state.error)
            put("canEdit", editable()); put("focus", state.focused?.name)
            DictionaryRuleField.entries.forEach { field ->
                put(field.name, state[field])
                put(field.name + "Start", state.selections[field.ordinal])
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
            val field = DictionaryRuleField.entries.find { it.name == key } ?: error("Unknown field")
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
                "focus" -> { check(editable()); model.focus(DictionaryRuleField.valueOf(args["key"] as String)) }
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
                            val request = model.fullEditRequest()
                            if (request == null) noFocus()
                            else try { fullEdit(request) } catch (failure: Exception) {
                                model.fullEditCancelled(); throw failure
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
