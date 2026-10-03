package io.legado.app.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.model.settings.OtherEffectReceipt

@Composable
internal fun OtherSettingsRoute(
    model: OtherSettingsViewModel,
    ready: () -> Boolean,
    effect: (OtherEffectReceipt) -> Unit,
    tree: (String) -> Unit,
    destination: (OtherDestination) -> Unit,
    search: String? = null,
    searchFinished: () -> Unit = {},
    searchEmpty: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available by rememberUpdatedState(ready)
    val handle by rememberUpdatedState(effect)
    val pick by rememberUpdatedState(tree)
    val navigate by rememberUpdatedState(destination)
    var pendingDestination by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (
                    available() &&
                        !value.loading &&
                        !value.failed &&
                        !value.busy &&
                        !value.pendingCommit &&
                        !value.writeFailed &&
                        !value.effectsWriting
                ) {
                    value.draft?.effects?.firstOrNull()?.let {
                        if (model.consumeEffect(it.id)) handle(it)
                    }
                    value.bookTreeEvent?.let { if (model.consumeBookTreeEvent(it)) pick(it) }
                }
            }
        }
    }
    LaunchedEffect(pendingDestination, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            pendingDestination?.let {
                if (available()) {
                    pendingDestination = null
                    navigate(OtherDestination.valueOf(it))
                }
            }
        }
    }
    BackHandler(state.busy || state.pendingCommit || state.interrupted) {}
    OtherSettingsScreen(
        state,
        OtherActions(
            model::boolean,
            model::edit,
            model::text,
            model::confirm,
            model::dismiss,
            model::retry,
            model::retryMutationConfirmed,
            model::pickBookTree,
            { pendingDestination = it.name },
        ),
        search,
        searchFinished,
        searchEmpty,
    )
}
