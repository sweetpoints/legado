package io.legado.app.ui.group

import androidx.lifecycle.Lifecycle
import io.legado.app.utils.GSON
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.map

internal fun namedGroupRowId(name: String): String = MessageDigest.getInstance("SHA-256")
    .digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** A binding owns commands until its engine detaches; all mutations stay in the Room ViewModel. */
internal class NamedGroupNativeCoordinator(
    private val model: NamedGroupViewModel,
    private val lifecycle: Lifecycle,
    private val kind: String,
    private val canDeliver: () -> Boolean,
    private val dismiss: () -> Unit,
) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private var owner: Any? = null
    private var closed = false
    private var dismissed = false
    private var rejected = false
    private var revision = 0L
    private var last: Map<String, Any?>? = null
    val changes = model.state.map { it }
    init { require(kind in setOf("book", "rss", "replace")) }
    fun bind(): Any = Any().also { check(!closed); owner = it; rejected = false }
    fun detach(binding: Any) { if (owner === binding) owner = null }
    private fun valid(binding: Any) = check(!closed && owner === binding) { "Retired group binding" }
    fun snapshot(binding: Any): Map<String, Any?> {
        valid(binding)
        val s = model.state.value
        val value = mapOf("generation" to generation, "kind" to kind, "showDone" to (kind == "rss"),
            "loading" to s.loading, "busy" to s.busy, "error" to s.error,
            "editing" to s.editing, "original" to s.original, "name" to s.name,
            "start" to s.selectionStart, "end" to s.selectionEnd,
            "recoveryFailed" to s.recoveryFailed, "mutationUncertain" to s.mutationUncertain,
            "groups" to s.groups.map { mapOf("id" to namedGroupRowId(it), "name" to it) })
        require(GSON.toJson(value).toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) { "Group snapshot is too large" }
        if (last != value) { revision++; last = value }
        return value + ("revision" to revision)
    }
    fun requestClose() {
        if (closed || dismissed || model.state.value.busy) return
        if (model.state.value.editing) { model.cancelEdit(); rejected = false }
        else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canDeliver()) {
            model.clearSession()
            dismissed = true
            dismiss()
        }
    }
    fun command(binding: Any, arguments: Any?): Map<String, Any?> {
        valid(binding)
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !dismissed)
        val args = arguments as? Map<*, *> ?: error("Invalid group command")
        require(args["generation"] == generation)
        val op = args["op"] as? String ?: error("Missing operation")
        if (op == "field") {
            try {
                check(model.state.value.let { it.editing && !it.busy && !it.recoveryFailed && !it.mutationUncertain })
                val text = args["text"] as? String ?: error("Missing text")
                val start = args["start"] as? Int ?: error("Missing start")
                val end = args["end"] as? Int ?: error("Missing end")
                model.input(text, start, end); rejected = false
            } catch (failure: Exception) { rejected = true; throw failure }
        } else {
            if (op !in setOf("cancel", "back")) check(!rejected) { "Unacknowledged input" }
            when (op) {
                "add" -> { check(!model.state.value.editing); model.add() }
                "edit", "delete" -> {
                    check(!model.state.value.editing && !model.state.value.busy && !model.state.value.loading)
                    val id = args["id"] as? String ?: error("Missing group")
                    val group = model.state.value.groups.singleOrNull { namedGroupRowId(it) == id }
                        ?: error("Group no longer exists")
                    if (op == "edit") model.edit(group) else model.delete(group)
                }
                "confirm" -> { check(model.state.value.let { it.editing && !it.busy && !it.recoveryFailed && !it.mutationUncertain }); model.confirm() }
                "cancel" -> { check(!model.state.value.busy); model.cancelEdit(); rejected = false }
                "retry" -> { check(!model.state.value.editing); model.retry() }
                "back" -> requestClose()
                else -> error("Unknown group operation")
            }
        }
        return snapshot(binding)
    }
    override fun close() { closed = true; owner = null }
    private companion object { val generations = AtomicInteger() }
}
