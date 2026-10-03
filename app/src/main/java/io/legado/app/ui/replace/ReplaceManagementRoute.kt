package io.legado.app.ui.replace

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
fun ReplaceManagementRoute(
    model: ReplaceManagementViewModel,
    ready: () -> Boolean,
    back: () -> Unit,
    native: (ReplaceManagementNative, String?) -> Unit,
    importRepository: ReplaceRulePreparedImportRepository? = null,
) {
    val context = LocalContext.current
    val importer =
        remember(context, importRepository) {
            importRepository
                ?: AppReplaceRulePreparedImportRepository(AppReplaceRuleImportRepository(context))
        }
    val state by model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val deliver by rememberUpdatedState(native)
    LaunchedEffect(state.pending, state.loaded, lifecycle, state.error, state.busy) {
        if (
            !state.loaded ||
                state.busy ||
                state.error != null ||
                lifecycle != Lifecycle.State.RESUMED ||
                !currentReady()
        )
            return@LaunchedEffect
        val pending = state.pending ?: return@LaunchedEffect
        var ownedImport: String? = null
        try {
            val request = model.native(pending.nonce) ?: return@LaunchedEffect
            if (
                pending.action in
                    listOf(ReplaceManagementAction.ImportUrl, ReplaceManagementAction.ImportInput)
            )
                ownedImport = importer.prepare(request.input.orEmpty())
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady())
                return@LaunchedEffect
            if (model.delivered(pending.nonce)) {
                deliver(request, ownedImport)
                ownedImport = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady())
                return@LaunchedEffect
            model.nativeFailed(error.localizedMessage ?: error.javaClass.simpleName)
        } finally {
            ownedImport?.let {
                withContext(Dispatchers.IO + NonCancellable) { importer.release(it) }
            }
        }
    }
    val currentBack by rememberUpdatedState(back)
    val actions =
        remember(model) {
            ReplaceManagementActions(
                { currentBack() },
                model::query,
                model::selected,
                model::selectAll,
                model::invertSelection,
                model::selectInterval,
                model::enabled,
                model::edge,
                model::dialog,
                model::draft,
                model::cancelDialog,
                model::confirmDialog,
                model::toggleManual,
                { action, source -> model.effect(action, source) },
                model::forgetImport,
                model::passphrase,
                model::copyFeedback,
                model::retry,
                model::beginSelection,
                model::selectionRange,
                model::finishSelection,
                model::beginDrag,
                model::dragTo,
                model::finishDrag,
                model::cancelGesture,
                model::scroll,
            )
        }
    ReplaceManagementScreen(state, actions)
}
