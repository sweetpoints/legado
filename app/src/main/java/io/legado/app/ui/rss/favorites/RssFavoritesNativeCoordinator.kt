package io.legado.app.ui.rss.favorites

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.entities.RssStar
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room and ReadRss retain authority; Flutter receives only the existing lightweight list
 * projection.
 */
internal class RssFavoritesNativeCoordinator(
    private val model: RssFavoriteListViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val showToolbar: Boolean,
    private val canDeliver: () -> Boolean,
    private val read: (RssStar) -> Unit,
    private val back: () -> Unit,
) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private var owner: Any? = null
    private var retired = false
    private var revision = 0L
    private var last: Map<String, Any?>? = null
    val state = model.state.map { snapshotProjection() }
    private val readLock = Mutex()
    private val delivery = scope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value -> deliverRead(value.pendingRead) }
        }
    }

    private suspend fun deliverRead(id: String?) = readLock.withLock {
        if (id == null || model.state.value.pendingRead != id || !canDeliver()) return@withLock
        try {
            val article = model.resolveRead(id)
            currentCoroutineContext().ensureActive()
            if (retired || lifecycle.currentState != Lifecycle.State.RESUMED || !canDeliver())
                return@withLock
            model.readDelivered(id)
            article?.let(read)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!retired && lifecycle.currentState == Lifecycle.State.RESUMED && canDeliver())
                model.readDelivered(id)
        }
    }

    fun onHostReady(scope: CoroutineScope) {
        scope.launch { deliverRead(model.state.value.pendingRead) }
    }

    fun bind(): Any =
        Any().also {
            check(!retired)
            owner = it
        }

    fun detach(binding: Any) {
        if (owner === binding) owner = null
    }

    private fun valid(binding: Any) =
        check(!retired && owner === binding) { "Retired favorites binding" }

    private fun snapshotProjection(): Map<String, Any?> =
        model.state.value.let { state ->
            mapOf(
                "generation" to generation,
                "toolbar" to showToolbar,
                "loaded" to state.loaded,
                "busy" to state.busy,
                "error" to (state.error != null),
                "groups" to state.groups,
                "group" to state.group,
                "rows" to
                    state.rows.map { row ->
                        mapOf(
                            "id" to row.id,
                            "group" to row.group,
                            "title" to row.title,
                            "date" to row.pubDate,
                            "image" to !row.image.isNullOrBlank(),
                            "imageKey" to
                                io.legado.app.data.repository.RoomRssFavoriteListRepository.key(
                                    row.image.orEmpty(),
                                    row.origin,
                                ),
                        )
                    },
                "positions" to
                    state.groups.associateWith { group ->
                        model.scrollPosition(group).let {
                            mapOf("index" to it.index, "offset" to it.offset)
                        }
                    },
                "confirmation" to
                    state.confirmation?.let {
                        mapOf("kind" to it.kind.name, "target" to it.target)
                    },
            )
        }

    fun snapshot(binding: Any): Map<String, Any?> {
        valid(binding)
        val value = snapshotProjection()
        if (last != value) {
            revision++
            last = value
        }
        return value + ("revision" to revision)
    }

    fun command(binding: Any, arguments: Any?): Map<String, Any?> {
        valid(binding)
        require(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val args = arguments as? Map<*, *> ?: error("Invalid favorites command")
        require(args["generation"] == generation)
        fun text(key: String) = args[key] as? String ?: error("Missing string")
        when (args["op"]) {
            "group" -> model.selectGroup(text("group"))
            "read" -> model.read(text("id"))
            "delete" -> model.requestDelete(text("id"))
            "deleteGroup" -> model.requestDeleteGroup()
            "deleteAll" -> {
                require(showToolbar)
                model.requestDeleteAll()
            }
            "confirm" -> model.confirmDelete()
            "cancel" -> model.cancelConfirmation()
            "retry" -> model.load()
            "scroll" -> {
                val index = args["index"] as? Int ?: error("Invalid index")
                val offset = args["offset"] as? Int ?: error("Invalid offset")
                require(index >= 0 && offset >= 0)
                model.scrolled(text("group"), index, offset)
            }
            "back" -> {
                require(
                    showToolbar && lifecycle.currentState == Lifecycle.State.RESUMED && canDeliver()
                )
                back()
            }
            else -> error("Unknown favorites operation")
        }
        return snapshot(binding)
    }

    fun image(id: String) = model.state.value.rows.firstOrNull { it.id == id }

    override fun close() {
        retired = true
        owner = null
        delivery.cancel()
    }

    private companion object {
        val generations = AtomicInteger()
    }
}
