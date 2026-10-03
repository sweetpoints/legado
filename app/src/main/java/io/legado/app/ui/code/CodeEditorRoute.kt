package io.legado.app.ui.code

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.code.EditSafety
import kotlinx.coroutines.CancellationException

/**
 * Transient engine reference for platform dialogs; never included in saved state or private JSON.
 */
internal class CodeEditorController {
    var engine: CodeEditorEngine? = null
    var owner: String? = null
    var exit: (() -> Unit)? = null
}

@Composable
internal fun CodeEditorRoute(
    model: CodeEditorComposeViewModel,
    controller: CodeEditorController,
    onPlatformAction: (CodeEditorAction) -> Unit,
    onReturn: (CodeEditorResultPayload) -> Unit,
    onClose: () -> Unit,
    canReturn: () -> Boolean,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    var autoWrap by remember { mutableStateOf(AppConfig.editAutoWrap) }
    var status by remember(state.engineOwner) { mutableStateOf(CodeEditorEngineStatus()) }
    val owner = state.engineOwner
    val session = state.session
    val safe = session != null && remember(owner) { EditSafety.isCombiningHeavy(session.text) }
    fun snapshotThen(action: () -> Unit) {
        val engine = controller.engine ?: return
        val capturedOwner = owner ?: return
        engine.snapshot { snapshot ->
            if (model.state.value.engineOwner != capturedOwner) return@snapshot
            model.updateEditor(
                capturedOwner,
                snapshot.text,
                snapshot.selection.start,
                snapshot.selection.end,
                snapshot.programmatic,
            )
            action()
        }
    }
    fun exit() {
        val engine = controller.engine
        if (engine?.dismissActions() == true) return
        if (session == null) {
            onClose()
        } else if (status.reading) {
            engine?.cancelRead()
            model.requestExit()
        } else if (status.ready && !status.replacing) {
            snapshotThen(model::requestExit)
        } else if (!state.busy && !status.replacing) {
            model.requestExit()
        }
    }
    SideEffect { controller.exit = ::exit }
    BackHandler(onBack = ::exit)
    DisposableEffect(lifecycleOwner, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controller.engine?.cancelRead()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session?.finished) {
        if (session?.finished == true) onClose()
    }
    val receipt = session?.returnReceipt
    LaunchedEffect(receipt?.id, receipt?.prepared, receipt?.claimed, state.busy, lifecycleState) {
        if (
            !state.busy &&
                receipt?.prepared == true &&
                !receipt.claimed &&
                lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            model.deliverReturn(
                receipt.id,
                canDeliver = {
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        canReturn()
                },
                deliver = onReturn,
            )
        }
    }
    CodeEditorScreen(
        state = state,
        status = status,
        safe = safe,
        keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
        keyboardRows = state.keyboardRows,
        autoWrap = autoWrap,
        assists = state.assists,
        onAction = { action ->
            val sora = controller.engine as? SoraCodeEditorEngine
            when (action) {
                CodeEditorAction.SAVE -> snapshotThen { model.save() }
                CodeEditorAction.DEBUG ->
                    snapshotThen { model.save(CodeEditActivity.RESULT_ACTION_DEBUG_SOURCE) }
                CodeEditorAction.LOGIN ->
                    snapshotThen { model.save(CodeEditActivity.RESULT_ACTION_LOGIN_SOURCE) }
                CodeEditorAction.SEARCH ->
                    if (owner != null && session != null) {
                        model.search(owner, session.search.copy(visible = !session.search.visible))
                        if (session.search.visible) controller.engine?.focus()
                    }
                CodeEditorAction.PREVIOUS -> sora?.previous()
                CodeEditorAction.NEXT -> sora?.next()
                CodeEditorAction.REPLACE ->
                    sora?.replaceCurrent(session?.search?.replacement.orEmpty())
                CodeEditorAction.REPLACE_ALL ->
                    sora?.replaceAll(session?.search?.replacement.orEmpty())
                CodeEditorAction.SELECT_ALL -> sora?.selectAll()
                CodeEditorAction.FORMAT -> sora?.format()
                CodeEditorAction.SYNTAX -> sora?.syntax()
                CodeEditorAction.UNDO -> controller.engine?.undo()
                CodeEditorAction.REDO -> controller.engine?.redo()
                CodeEditorAction.WRAP -> {
                    onPlatformAction(action)
                    autoWrap = AppConfig.editAutoWrap
                }
                else -> onPlatformAction(action)
            }
        },
        onSearch = { search ->
            if (owner != null) model.search(owner, search)
            if (!search.visible) controller.engine?.focus()
        },
        onInsert = { controller.engine?.insert(it) },
        onExit = ::exit,
        onRetry = model::retry,
        onRestart = { if (owner != null) model.restartEngine(owner) },
        onKeepEditing = {
            model.keepEditing()
            controller.engine?.restoreEditing()
        },
        onDiscard = model::discard,
        editorContent = { modifier ->
            if (owner != null && session != null && !session.finished) {
                key(owner) {
                    CodeEditorNativeSurface(
                        model,
                        owner,
                        session,
                        safe,
                        controller,
                        inputEnabled = !state.busy && session.returnReceipt == null,
                        onStatus = { status = it },
                        modifier = modifier,
                    )
                }
            }
        },
        modifier = Modifier.imePadding(),
    )
}

@Composable
private fun CodeEditorNativeSurface(
    model: CodeEditorComposeViewModel,
    owner: String,
    session: CodeEditorSession,
    safe: Boolean,
    controller: CodeEditorController,
    inputEnabled: Boolean,
    onStatus: (CodeEditorEngineStatus) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val language =
        remember(owner) { if (safe) null else CodeEditorLanguageEngine(context.applicationContext) }
    var prepared by remember(owner) { mutableStateOf(safe) }
    var engine by remember(owner) { mutableStateOf<CodeEditorEngine?>(null) }
    LaunchedEffect(owner) {
        controller.owner = owner
        try {
            language?.prepare(session)
            prepared = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onStatus(CodeEditorEngineStatus(failed = true))
        }
    }
    DisposableEffect(owner) {
        onDispose {
            engine?.dispose()
            language?.dispose()
            if (controller.owner == owner) {
                controller.engine = null
                controller.owner = null
            }
        }
    }
    if (prepared) {
        AndroidView(
            factory = { viewContext ->
                val changed: (CodeEditorSnapshot) -> Unit = { snapshot ->
                    model.updateEditor(
                        owner,
                        snapshot.text,
                        snapshot.selection.start,
                        snapshot.selection.end,
                        snapshot.programmatic,
                    )
                }
                val status: (CodeEditorEngineStatus) -> Unit = { value ->
                    if (model.state.value.engineOwner == owner) {
                        onStatus(value)
                        model.editorReady(owner, value.ready)
                    }
                }
                val active = { model.state.value.engineOwner == owner }
                val created =
                    if (safe) SafeCodeEditorEngine(viewContext, session, changed, status, active)
                    else
                        SoraCodeEditorEngine(
                            viewContext,
                            session,
                            checkNotNull(language),
                            changed,
                            status,
                            active,
                        )
                engine = created
                controller.engine = created
                controller.owner = owner
                created.view
            },
            update = {
                engine?.setInputEnabled(inputEnabled)
                (engine as? SoraCodeEditorEngine)?.search(session.search)
            },
            modifier = modifier,
        )
    }
}
