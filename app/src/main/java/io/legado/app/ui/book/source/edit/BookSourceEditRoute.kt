package io.legado.app.ui.book.source.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun BookSourceEditRoute(
    model: BookSourceComposeViewModel,
    onNative: (BookSourceNativeRequest) -> Unit,
    onRegister: (BookSourceNativeRequest?) -> Unit,
    onFinish: (String?) -> Unit,
    onSaved: (String) -> Unit,
    onPaste: () -> Unit,
    canHandle: () -> Boolean,
    initialHelpNeeded: Boolean,
    maxLines: Int,
    keyboardRows: Int,
    transparentBackground: Boolean,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launch by rememberUpdatedState(onNative)
    val register by rememberUpdatedState(onRegister)
    val finish by rememberUpdatedState(onFinish)
    val saved by rememberUpdatedState(onSaved)
    val ready by rememberUpdatedState(canHandle)
    BackHandler(enabled = !state.busy) { model.cancel() }
    LaunchedEffect(model, lifecycle) {
        dispatchBookSourceEditorEffects(
            model = model,
            lifecycle = lifecycle,
            canHandle = { ready() },
            onNative = { launch(it) },
            onRegister = { register(it) },
            onFinish = { finish(it) },
            onSaved = { saved(it) },
            initialHelpNeeded = initialHelpNeeded,
        )
    }
    val actions =
        BookSourceScreenActions(
            field = model::updateField,
            focus = model::focus,
            tab = model::tab,
            options = model::options,
            expanded = model::optionsExpanded,
            save = model::save,
            native = model::requestAction,
            autoComplete = model::autoComplete,
            paste = onPaste,
            clearCookie = model::clearCookie,
            insert = model::insert,
            undo = { model.undo() },
            redo = { model.undo(redo = true) },
            groups = model::groups,
            dismissGroups = model::dismissGroups,
            variableEdit = model::variableEdit,
            variableSave = model::variable,
            dismissVariable = model::dismissVariable,
            cancel = model::cancel,
            discard = model::discard,
            keepEditing = model::keepEditing,
            retry = model::retry,
            migration = model::previewMigration,
        )
    BookSourceEditScreen(
        state,
        actions,
        maxLines,
        state.keyboardRows ?: keyboardRows,
        keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
        transparentBackground = transparentBackground,
    )
    BookSourceMigrationDialog(
        state = state,
        onDismiss = model::dismissMigration,
        onApply = model::applyMigration,
        onRetry = model::previewMigration,
    )
}
