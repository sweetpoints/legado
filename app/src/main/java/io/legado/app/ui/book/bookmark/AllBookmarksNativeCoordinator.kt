package io.legado.app.ui.book.bookmark

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.AllBookmarksDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

/** UI bindings cannot launch activities; native delivery is claimed once, only while resumed. */
internal class AllBookmarksNativeCoordinator(
    private val model: AllBookmarksViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val closeHost: () -> Unit,
    private val open: (AllBookmarksDestination, Int) -> Unit,
    private val directory: (Boolean) -> Unit,
    private val notice: (String) -> Unit,
    private val exported: String,
    private val unknown: String,
) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private var owner: Any? = null
    private var closed = false
    private var revision = 0L
    private var last: Map<String, Any?>? = null
    val changes = model.state
    private val delivery = scope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                model.state.map { it.effect }.distinctUntilChanged().collect { effect ->
                    effect ?: return@collect
                    if (!ready()) return@collect
                    try {
                        val destination = if (effect.type == AllBookmarksEffectType.Open) model.resolveOpen(effect) else null
                        currentCoroutineContext().ensureActive()
                        if (closed || lifecycle.currentState != Lifecycle.State.RESUMED || !ready()) return@collect
                        model.delivered(effect.nonce) ?: return@collect
                        when (effect.type) {
                            AllBookmarksEffectType.Open -> if (destination != null) open(destination,
                                model.state.value.rows.indexOfFirst { it.id == effect.bookmark }.coerceAtLeast(0))
                            AllBookmarksEffectType.Directory -> directory(effect.markdown)
                            AllBookmarksEffectType.Exported -> notice(exported)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (!closed && lifecycle.currentState == Lifecycle.State.RESUMED && ready()) {
                            model.delivered(effect.nonce)
                            model.deliveryFailed(effect, error.localizedMessage ?: unknown)
                        }
                    }
                }
            }
            launch {
                model.state.map { it.error }.distinctUntilChanged().collect { error ->
                    if (error != null && !closed && ready()) { model.clearError(); notice(error) }
                }
            }
        }
    }
    fun bind(): Any = Any().also { check(!closed); owner = it }
    fun detach(binding: Any) { if (owner === binding) owner = null }
    private fun valid(binding: Any) = check(!closed && owner === binding) { "Retired bookmarks binding" }
    fun snapshot(binding: Any): Map<String, Any?> {
        valid(binding)
        val state = model.state.value
        val value = mapOf("generation" to generation, "loaded" to state.loaded,
            "exporting" to state.exporting, "pending" to (state.effect != null),
            "error" to state.error, "scroll" to state.scroll, "offset" to state.offset,
            "rows" to state.rows.map { mapOf("id" to it.id.toString(), "bookName" to it.bookName,
                "bookAuthor" to it.bookAuthor, "chapter" to it.chapter, "original" to it.original, "content" to it.content) })
        if (value != last) { revision++; last = value }
        return value + ("revision" to revision)
    }
    fun command(binding: Any, arguments: Any?): Map<String, Any?> {
        valid(binding)
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val args = arguments as? Map<*, *> ?: error("Invalid bookmarks command")
        require(args["generation"] == generation)
        val op = args["op"] as? String ?: error("Missing operation")
        val keys = when (op) { "open" -> setOf("op", "generation", "id", "edit")
            "export" -> setOf("op", "generation", "markdown")
            "scroll" -> setOf("op", "generation", "index", "offset")
            "back", "retry" -> setOf("op", "generation")
            else -> error("Unknown bookmarks operation") }
        require(args.keys == keys)
        when (op) {
            "open" -> model.open((args["id"] as? String)?.toLongOrNull() ?: error("Invalid bookmark identity"), args["edit"] as? Boolean ?: error("Missing edit"))
            "export" -> model.requestExport(args["markdown"] as? Boolean ?: error("Missing format"))
            "scroll" -> model.scroll(args["index"] as? Int ?: error("Missing index"), args["offset"] as? Int ?: error("Missing offset"))
            "retry" -> model.observe()
            "back" -> if (lifecycle.currentState == Lifecycle.State.RESUMED && ready()) closeHost()
        }
        return snapshot(binding)
    }
    override fun close() { closed = true; owner = null; delivery.cancel() }
    private companion object { val generations = AtomicInteger() }
}
