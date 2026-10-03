package io.legado.app.ui.association.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.association.AssociationNativeReceipt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** Platform preparation may do IO; dispatch itself is a synchronous native acceptance boundary. */
data class PreparedAssociationDelivery(
    val deliver: () -> Unit,
    val awaitsResult: Boolean = false,
)

@Composable
fun AssociationImportRoute(
    model: AssociationImportViewModel,
    configuredDirectory: String?,
    privateDirectory: String,
    canDeliver: () -> Boolean,
    prepare: suspend (AssociationNativeReceipt) -> PreparedAssociationDelivery,
    onDeliveryError: (Throwable) -> Unit,
    onChoosePrivateDirectory: () -> Unit,
    onClose: () -> Unit,
    showSessionErrors: Boolean = true,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentCanDeliver by rememberUpdatedState(canDeliver)
    val currentPrepare by rememberUpdatedState(prepare)
    val currentError by rememberUpdatedState(onDeliveryError)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.reconcileNativeResults()
            model.state.collectLatest { current ->
                if (
                    !current.loaded ||
                        current.busy ||
                        current.nativeResultPending ||
                        !currentCanDeliver()
                )
                    return@collectLatest
                for (receipt in current.session?.effects.orEmpty()) {
                    var claimed: AssociationNativeReceipt? = null
                    var accepted = false
                    var awaitsResult = false
                    try {
                        val delivery = currentPrepare(receipt)
                        currentCoroutineContext().ensureActive()
                        if (
                            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                                !currentCanDeliver()
                        )
                            break
                        claimed = model.claimNative(receipt) ?: continue
                        currentCoroutineContext().ensureActive()
                        if (
                            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                                !currentCanDeliver()
                        )
                            break
                        // No suspension separates this owner check from platform dispatch.
                        delivery.deliver()
                        accepted = true
                        awaitsResult = delivery.awaitsResult
                    } catch (failure: Throwable) {
                        currentCoroutineContext().ensureActive()
                        currentError(failure)
                        break
                    } finally {
                        claimed?.let { owned ->
                            withContext(NonCancellable) {
                                // An unaccepted claim returns to pending on pause. A picker keeps
                                // its claim until its matching result; delivered screens ack once.
                                runCatching {
                                    if (!accepted) model.returnNative(owned)
                                    else if (!awaitsResult) model.acknowledgeNative(owned)
                                }
                                    .onFailure { failure ->
                                        // A real close may already have fenced/released the
                                        // session.
                                        // Report the cleanup failure without replaying accepted UI.
                                        if (currentCanDeliver()) currentError(failure)
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
    AssociationImportScreen(
        state = state,
        configuredDirectory = configuredDirectory,
        privateDirectory = privateDirectory,
        onConfirmReadConfig = { model.confirmOperation("read-config") },
        onConfirmUnsupported = { model.confirmOperation("unsupported") },
        onChooseSystemDirectory = model::chooseSystemDirectory,
        onChoosePrivateDirectory = onChoosePrivateDirectory,
        onCancelDirectory = model::cancelDirectory,
        onClose = onClose,
        showSessionErrors = showSessionErrors,
    )
}
