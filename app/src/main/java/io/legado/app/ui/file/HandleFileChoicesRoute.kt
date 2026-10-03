package io.legado.app.ui.file

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.HandleFileIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

@Composable
fun HandleFileChoicesRoute(
    model: HandleFileChoicesViewModel,
    canDeliver: () -> Boolean,
    native: suspend (String, Int, String) -> Unit,
    result: (String, String?) -> Unit,
    close: (HandleFileIssue?) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by
        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(
            minActiveState = Lifecycle.State.CREATED
        )
    val ready by rememberUpdatedState(canDeliver)
    val launchNative by rememberUpdatedState(native)
    val deliverResult by rememberUpdatedState(result)
    val dismiss by rememberUpdatedState(close)
    BackHandler { model.close() }

    // Busy changes during receipt persistence must not cancel the coroutine that writes it.
    LaunchedEffect(lifecycle, state.phase, state.pending?.nonce) {
        if (lifecycle != Lifecycle.State.RESUMED || state.phase != "Native") return@LaunchedEffect
        val current = model.state.first { !it.busy }
        if (!current.loaded || current.finished || !ready()) return@LaunchedEffect
        val pending = current.pending ?: return@LaunchedEffect
        try {
            if (!pending.delivered && !model.nativeDelivered(pending.nonce)) return@LaunchedEffect
            currentCoroutineContext().ensureActive()
            if (!ready() || model.state.value.phase != "Native") return@LaunchedEffect
            // Restored system launches wait for their registered result. Permission handshakes and
            // app dialogs can instead resume safely in the recreated host without another
            // transport.
            if (!pending.delivered || pending.action in listOf(10, 11, 112, 113)) {
                launchNative(pending.nonce, pending.action, current.draft)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            model.nativeFailed(pending.nonce, error)
        }
    }
    LaunchedEffect(lifecycle, state.result, state.error) {
        if (lifecycle != Lifecycle.State.RESUMED || !ready()) return@LaunchedEffect
        val current = model.state.first { !it.busy }
        val uri = current.result ?: return@LaunchedEffect
        if (current.phase != "Result" || current.error != null) return@LaunchedEffect
        try {
            if (model.resultDelivered()) {
                var delivered = false
                try {
                    currentCoroutineContext().ensureActive()
                    if (ready()) {
                        val value =
                            current.input?.value.takeUnless {
                                current.input?.mode == HandleFileContract.EXPORT
                            }
                        deliverResult(uri, value)
                        delivered = true
                    }
                } finally {
                    if (!delivered) model.deferResultDelivery()
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            model.resultDeliveryFailed(error)
        }
    }
    LaunchedEffect(lifecycle, state.finished) {
        if (
            lifecycle == Lifecycle.State.RESUMED &&
                state.finished &&
                state.phase != "Result" &&
                ready()
        ) {
            dismiss(state.issue)
        }
    }
    HandleFileChoicesScreen(
        state = state,
        choose = model::choose,
        textChanged = model::text,
        confirm = model::confirmManual,
        retry = model::retry,
        close = model::close,
    )
}
