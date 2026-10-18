package io.legado.app.ui.source.debug

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.ui.book.source.debug.BookSourceDebugStage
import io.legado.app.ui.book.source.debug.BookSourceDebugViewModel
import io.legado.app.ui.rss.source.debug.RssSourceDebugViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map

/** Android retains the authoritative run lease, private log record and lifecycle ownership. */
class SourceDebuggerNativeCoordinator private constructor(
    val kind: String,
    val generation: Int,
    val state: Flow<Map<String, Any?>>,
    private val current: () -> Map<String, Any?>,
    private val execute: (SourceDebuggerCommand) -> Unit,
    private val closeModel: () -> Unit,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val canHandle: () -> Boolean,
    private val onFinish: () -> Unit,
    flush: suspend () -> Unit,
) : AutoCloseable {
    private var binding: Any? = null
    private var retired = false
    private var finished = false
    private var lastProjection: Map<String, Any?>? = null
    private var revision = 0L
    private val effects = scope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.collect(::completeIfReady)
        }
    }
    private val checkpoint = scope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { runCatching { flush() } } }
        }
    }

    fun bind(): Any {
        check(!retired)
        return Any().also { binding = it }
    }

    fun detach(owner: Any) { if (binding === owner) binding = null }

    fun snapshot(owner: Any): Map<String, Any?> {
        requireOwner(owner)
        val projection = current()
        if (projection != lastProjection) {
            check(revision < Long.MAX_VALUE) { "Debugger revision exhausted" }
            revision++
            lastProjection = projection
        }
        return projection + ("revision" to revision)
    }

    fun command(owner: Any, arguments: Any?): Map<String, Any?>? {
        requireOwner(owner)
        val command = parseSourceDebuggerCommand(arguments, kind, generation)
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        check(command.op == "close" || current()["closed"] != true)
        if (command.op in setOf("html", "scan", "instructions", "openUrl")) {
            check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canHandle())
        }
        execute(command)
        // A selection/composing update can leave StateFlow equal and emit nothing. A direct
        // query acknowledgement releases Dart's pending input without waiting for an event.
        return if (command.op == "query") snapshot(owner) else null
    }

    private fun requireOwner(owner: Any) { check(!retired && binding === owner) { "Stale debugger binding" } }

    fun requestClose() { if (!retired) closeModel() }

    /** FragmentManager's saved-state gate is independent from the model's StateFlow. */
    fun onHostReady() { if (!retired) completeIfReady(current()) }

    private fun completeIfReady(value: Map<String, Any?>) {
        // Explicit Back cancels initialization, which can leave loading=true indefinitely.
        if (!retired && !finished && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            canHandle() && (value["closed"] == true ||
                (value["loading"] == false && value["missing"] == true))) {
            finished = true
            closeModel()
            onFinish()
        }
    }

    /** Detaching UI does not stop the ViewModel's task or delete its private checkpoint. */
    override fun close() {
        if (retired) return
        retired = true
        binding = null
        effects.cancel()
        checkpoint.cancel()
    }

    companion object {
        private val generations = AtomicInteger()
        private fun nextGeneration(): Int = generations.updateAndGet { if (it == Int.MAX_VALUE) 1 else it + 1 }

        fun book(
            model: BookSourceDebugViewModel, lifecycle: Lifecycle, scope: CoroutineScope,
            canHandle: () -> Boolean, onFinish: () -> Unit, onHtml: (String?) -> Unit,
            onScan: () -> Unit, onInstructions: () -> Unit, onOpenUrl: (String) -> Unit,
        ): SourceDebuggerNativeCoordinator {
            val generation = nextGeneration()
            return SourceDebuggerNativeCoordinator("book", generation,
                model.state.map { bookSourceDebuggerSnapshot(it, generation) },
                { bookSourceDebuggerSnapshot(model.state.value, generation) },
                { command -> when (command.op) {
                    "query" -> model.query(checkNotNull(command.text), checkNotNull(command.start), checkNotNull(command.end))
                    "help" -> model.help(checkNotNull(command.value))
                    "run" -> model.run()
                    "sort" -> {
                        val index = checkNotNull(command.index)
                        require(index in model.state.value.sorts.indices)
                        require(!model.state.value.sorts[index].name.startsWith("ERROR:"))
                        model.sort(index)
                    }
                    "html" -> onHtml(model.html(when (command.stage) {
                        "search" -> BookSourceDebugStage.Search
                        "book" -> BookSourceDebugStage.Book
                        "toc" -> BookSourceDebugStage.Toc
                        "content" -> BookSourceDebugStage.Content
                        else -> error("Invalid stage")
                    }))
                    "retry" -> model.retry()
                    "close" -> model.close()
                    "scan" -> onScan()
                    "refreshExplore" -> model.refreshExplore()
                    "instructions" -> onInstructions()
                    "prefix" -> model.prefix(checkNotNull(command.text))
                    "detail" -> model.detail()
                    "openUrl" -> onOpenUrl(checkNotNull(command.url))
                } }, model::close, lifecycle, scope, canHandle, onFinish, model::flush)
        }

        fun rss(
            model: RssSourceDebugViewModel, lifecycle: Lifecycle, scope: CoroutineScope,
            canHandle: () -> Boolean, onFinish: () -> Unit, onHtml: (String?) -> Unit,
            onOpenUrl: (String) -> Unit,
        ): SourceDebuggerNativeCoordinator {
            val generation = nextGeneration()
            return SourceDebuggerNativeCoordinator("rss", generation,
                model.state.map { rssSourceDebuggerSnapshot(it, generation) },
                { rssSourceDebuggerSnapshot(model.state.value, generation) },
                { command -> when (command.op) {
                    "query" -> model.query(checkNotNull(command.text), checkNotNull(command.start), checkNotNull(command.end))
                    "help" -> model.help(checkNotNull(command.value))
                    "run" -> model.run()
                    "sort" -> {
                        val index = checkNotNull(command.index)
                        require(index in model.state.value.sorts.indices)
                        require(!model.state.value.sorts[index].name.startsWith("ERROR:"))
                        model.sort(index)
                    }
                    "html" -> onHtml(model.html(command.stage == "content"))
                    "retry" -> model.retry()
                    "close" -> model.close()
                    "openUrl" -> onOpenUrl(checkNotNull(command.url))
                } }, model::close, lifecycle, scope, canHandle, onFinish, model::flush)
        }
    }
}
