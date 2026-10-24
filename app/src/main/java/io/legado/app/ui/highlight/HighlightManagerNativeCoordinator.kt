package io.legado.app.ui.highlight

import androidx.lifecycle.Lifecycle
import io.legado.app.data.repository.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Native lifecycle owns one-shot platform effects and transfer files, independent of UI engines. */
internal class HighlightManagerNativeCoordinator(
    private val model: HighlightManagementViewModel,
    private val transfer: HighlightManagementTransferRepository,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val closeHost: () -> Unit,
    private val deliver: (HighlightManagementEffect, ByteArray?, File?) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val updates = MutableStateFlow(0L)
    private var closed = false
    private var closeDelivered = false
    private var failedToken: String? = null
    private fun available() = !closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()
    private val job = scope.launch {
        combine(model.state, lifecycle.currentStateFlow, updates) { state, _, _ -> state }.collect { current ->
            if (!available()) return@collect
            if (current.closed && !closeDelivered) { closeDelivered = true; closeHost(); return@collect }
            if (!current.loaded || !current.rowsReady || current.busy) return@collect
            val effect = current.draft.effects.firstOrNull() ?: return@collect
            if (effect.token == failedToken && current.error != null) return@collect
            var file: File? = null
            var dispatched = false
            try {
                val bytes = if (effect.action == HighlightManagementAction.Export) transfer.export(effect) else null
                if (effect.action == HighlightManagementAction.Share) file = transfer.share(effect)
                currentCoroutineContext().ensureActive()
                if (!available()) return@collect
                model.consume(effect.token, ::available)?.let { claimed ->
                    currentCoroutineContext().ensureActive(); dispatched = true; deliver(claimed, bytes, file)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { model.consume(effect.token, ::available) }
                if (effect.action == HighlightManagementAction.Export) model.exportResult(null, effect.token)
                failedToken = effect.token; model.deliveryFailure(error); onError(error.localizedMessage ?: "Error")
            } finally { if (!dispatched) withContext(NonCancellable + Dispatchers.IO) { file?.delete() } }
        }
    }
    fun onHostReady() { updates.value++ }
    override fun close() { closed = true; job.cancel() }
}
