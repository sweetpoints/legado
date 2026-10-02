package io.legado.app.ui.autoTask

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun AutoTaskManagementRoute(model: AutoTaskManagementViewModel,
    onEffect: (AutoTaskManagementEffect, String?) -> Unit,
    onError: (String) -> Unit,
    canHandle: () -> Boolean = { true }) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onEffect)
    val error by rememberUpdatedState(onError)
    val ready by rememberUpdatedState(canHandle)
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { current ->
                if (!ready() || current.busy) return@collect
                for (effect in current.effects) {
                    if (!ready()) break
                    var settled = false
                    try {
                        // Keep tickets until their disk payload is available and this host is resumed.
                        val payload = when (effect.action) {
                            AutoTaskManagementAction.Export -> model.exportText(effect)
                            AutoTaskManagementAction.ImportDraft -> model.importText(effect.value!!)
                            else -> null
                        }
                        currentCoroutineContext().ensureActive()
                        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !ready()) break
                        model.consume(effect.id)
                        settled = true
                        deliver(effect, payload)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        // A damaged receipt must not block later actions. The menus explicitly
                        // generate a fresh import/export request when the user retries.
                        if (!settled) { model.consume(effect.id); settled = true }
                        error(failure.localizedMessage ?: "Error")
                    } finally {
                        if (settled && effect.action == AutoTaskManagementAction.Export) {
                            withContext(NonCancellable) {
                                // Cleanup also runs when the native file launcher throws.
                                runCatching { model.releaseExport(effect) }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { model.flushDraft() } }
        }
    }
    val actions = remember(model) { AutoTaskManagementActions(
        model::action, model::query, model::select, model::selectAll, model::invert,
        model::enabled, model::selectedEnabled, model::move, model::askDelete, model::delete, model::closeDelete,
        model::showLog, model::clearLog, model::closeLog, model::openCron, model::cronDraft, model::saveCron, model::closeCron,
        model::openOnline, model::onlineInput, model::importOnline, model::closeOnline, model::removeHistory,
        model::export, model::copyExport, model::closeExportNotice, model::observe,
        model::beginSlide, model::slideTo, model::endSlide, model::cancelSlide) }
    BackHandler {
        when {
            state.busy -> Unit
            state.deleteIds != null -> model.closeDelete()
            state.logId != null -> model.closeLog()
            state.cronIds != null -> model.closeCron()
            state.online -> model.closeOnline()
            state.exportNotice != null -> model.closeExportNotice()
            else -> model.action(AutoTaskManagementAction.Close)
        }
    }
    AutoTaskManagementScreen(state, actions)
}
