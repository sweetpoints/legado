package io.legado.app.ui.book.source.edit

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

/** The UI may change; durable native request ownership and delivery remain identical. */
internal suspend fun dispatchBookSourceEditorEffects(
    model: BookSourceComposeViewModel,
    lifecycle: Lifecycle,
    canHandle: () -> Boolean,
    onNative: (BookSourceNativeRequest) -> Unit,
    onRegister: (BookSourceNativeRequest?) -> Unit,
    onFinish: (String?) -> Unit,
    onSaved: (String) -> Unit,
    initialHelpNeeded: Boolean,
) {
    var finished = false
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        model.state.collect { current ->
            if (!canHandle()) return@collect
            val document = current.document ?: return@collect
            onRegister(document.nativeRequest)
            document.savedUrl?.let(onSaved)
            if (document.finished) {
                if (!finished) {
                    finished = true
                    onFinish(document.savedUrl)
                }
            } else if (!current.busy && current.error == null) {
                val request = document.nativeRequest
                if (request != null && !request.delivered) {
                    model.deliverNative(
                        request.id,
                        { canHandle() && lifecycle.currentState == Lifecycle.State.RESUMED },
                        onNative,
                    )
                } else if (request == null && !document.helpShown) {
                    model.showInitialHelp(initialHelpNeeded)
                }
            }
        }
    }
}
