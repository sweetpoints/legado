package io.legado.app.ui.book.toc.rule

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Consumes a durable receipt before the native callback, including callback failures. */
internal class TxtTocManagerNativeCoordinator(
    private val model: TxtTocRuleManagementViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val picker: Boolean,
    private val deliver: (TxtTocManagementEffect) -> Unit,
    private val failed: (Throwable) -> Unit,
    private val closeHost: () -> Unit,
) : AutoCloseable {
    private val updates = MutableStateFlow(0L)
    private var closed = false
    private var finished = false
    private fun deliverable() = !closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()
    private val job = scope.launch {
        combine(model.state, lifecycle.currentStateFlow, updates) { state, _, _ -> state }.collect { state ->
            if (!deliverable() || state.loading || state.busy) return@collect
            try {
                if (!model.privateStateReady) return@collect
                if (model.state.value.effect == null) model.persist()
                if (!deliverable()) return@collect
                model.consumeEffect(::deliverable)?.let { effect ->
                    try { deliver(effect) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { failed(error) }
                }
                if (deliverable() && picker && model.state.value.effect == null && model.state.value.pickerFinished && !finished) {
                    finished = true
                    closeHost()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(error) }
        }
    }
    fun onHostReady() { updates.value++ }
    override fun close() { closed = true; job.cancel() }
}
