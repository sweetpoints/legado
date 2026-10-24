package io.legado.app.ui.replace

import androidx.lifecycle.Lifecycle
import io.legado.app.data.repository.ReplaceRulePreparedImportRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns prepared imports until a resumed, live Activity accepts its one-shot effect. */
internal class ReplaceManagerNativeCoordinator(
    private val model: ReplaceManagementViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val native: (ReplaceManagementNative, String?) -> Unit,
    private val changed: (Boolean) -> Unit,
    private val importer: ReplaceRulePreparedImportRepository,
    private val releaseDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val updates = MutableStateFlow(0L)
    private var closed = false
    private val job = scope.launch {
        combine(model.state, lifecycle.currentStateFlow, updates) { state, _, _ -> state }
            .distinctUntilChangedBy { listOf(it.pending, it.loaded, it.busy, it.error, it.changed, lifecycle.currentState, updates.value) }
            .collectLatest { state ->
                if (!deliverable()) return@collectLatest
                changed(state.changed)
                if (!state.loaded || state.busy || state.error != null) return@collectLatest
                val effect = state.pending ?: return@collectLatest
                var owned: String? = null
                try {
                    val request = model.native(effect.nonce) ?: return@collectLatest
                    if (effect.action in setOf(ReplaceManagementAction.ImportUrl, ReplaceManagementAction.ImportInput)) owned = importer.prepare(request.input.orEmpty())
                    currentCoroutineContext().ensureActive()
                    if (!deliverable()) return@collectLatest
                    if (model.delivered(effect.nonce)) { native(request, owned); owned = null }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (deliverable()) model.nativeFailed(error.localizedMessage ?: error.javaClass.simpleName)
                } finally {
                    owned?.let { value -> withContext(releaseDispatcher + NonCancellable) { importer.release(value) } }
                }
            }
    }
    private fun deliverable() = !closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()
    fun onHostReady() { updates.value++ }
    override fun close() { closed = true; job.cancel() }
}
