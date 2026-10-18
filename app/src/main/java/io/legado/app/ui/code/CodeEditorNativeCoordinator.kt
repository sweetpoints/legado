package io.legado.app.ui.code

import android.content.Context
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.code.EditSafety
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CodeEditorPresentation(
    val state: CodeEditorComposeState = CodeEditorComposeState(),
    val status: CodeEditorEngineStatus = CodeEditorEngineStatus(),
    val generation: Int = 0,
    val safe: Boolean = false,
    val autoWrap: Boolean = false,
)

/** Owns presentation lifetime only; text and durable return receipts stay in the existing VM. */
internal class CodeEditorNativeCoordinator(
    private val model: CodeEditorComposeViewModel,
    private val controller: CodeEditorController,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val onPlatformAction: (CodeEditorAction) -> Unit,
    private val onReturn: (CodeEditorResultPayload) -> Unit,
    private val onClose: () -> Unit,
    private val canReturn: () -> Boolean,
) : AutoCloseable {
    private val mutable = MutableStateFlow(CodeEditorPresentation(autoWrap = AppConfig.editAutoWrap))
    val state = mutable.asStateFlow()
    private var owner: String? = null
    private var generation = 0
    private var closed = false
    private val surfaces = mutableSetOf<CodeEditorPlatformSurface>()
    private val pauseObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) controller.engine?.cancelRead()
    }
    private val modelObservation: Job
    private val returns: Job

    init {
        lifecycle.addObserver(pauseObserver)
        controller.exit = ::exit
        modelObservation = scope.launch {
            model.state.collect { current ->
                if (owner != current.engineOwner) {
                    owner = current.engineOwner
                    if (owner != null) generation++
                    surfaces.toList().forEach { it.close() }
                    mutable.value = CodeEditorPresentation(
                        state = current,
                        generation = if (owner == null) 0 else generation,
                        safe = current.session?.let { EditSafety.isCombiningHeavy(it.text) } ?: false,
                        autoWrap = AppConfig.editAutoWrap,
                    )
                } else mutable.value = mutable.value.copy(
                    state = current,
                    // Bootstrap publishes its fresh owner before publishing the accepted session.
                    // Compute safety when that session first arrives, not from the empty owner state.
                    safe = if (mutable.value.state.session == null && current.session != null)
                        EditSafety.isCombiningHeavy(current.session.text) else mutable.value.safe,
                )
                surfaces.toList().forEach { it.update(current) }
            }
        }
        returns = scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { current ->
                    if (current.session?.finished == true) { if (canReturn()) onClose(); return@collect }
                    val receipt = current.session?.returnReceipt
                    if (!current.busy && receipt?.prepared == true && !receipt.claimed) {
                        model.deliverReturn(receipt.id, {
                            !closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canReturn()
                        }, onReturn)
                    }
                }
            }
        }
    }

    internal fun createSurface(context: Context, requestedGeneration: Int): CodeEditorPlatformSurface {
        check(!closed && requestedGeneration > 0 && requestedGeneration == state.value.generation) { "Stale editor surface" }
        val current = model.state.value
        val capturedOwner = checkNotNull(current.engineOwner)
        val session = checkNotNull(current.session)
        check(!session.finished && current.engineOwner == owner) { "Editor session is unavailable" }
        surfaces.toList().forEach { it.close() }
        mutable.value = mutable.value.copy(status = CodeEditorEngineStatus())
        model.editorReady(capturedOwner, false)
        return CodeEditorPlatformSurface(context, model, controller, capturedOwner, session,
            state.value.safe, scope, { value ->
                if (!closed && owner == capturedOwner) mutable.value = mutable.value.copy(status = value)
            }, { surfaces.remove(it) }).also { surfaces.add(it); it.update(current) }
    }

    private fun engine(): CodeEditorEngine {
        val current = model.state.value
        check(!closed && !current.busy && current.session?.finished == false && current.session.returnReceipt == null) { "Editor is busy" }
        check(current.engineOwner != null && current.engineOwner == controller.owner && current.engineOwner == owner) { "Stale editor owner" }
        val status = state.value.status
        check(status.ready && !status.failed && !status.reading && !status.replacing && current.editorReady) { "Editor is not ready" }
        return checkNotNull(controller.engine)
    }

    private fun snapshotThen(action: () -> Unit) {
        val engine = engine()
        val capturedOwner = checkNotNull(owner)
        engine.snapshot { snapshot ->
            if (closed || model.state.value.engineOwner != capturedOwner || controller.owner != capturedOwner) return@snapshot
            model.updateEditor(capturedOwner, snapshot.text, snapshot.selection.start,
                snapshot.selection.end, snapshot.programmatic)
            action()
        }
    }

    fun action(action: CodeEditorAction) {
        val current = model.state.value
        val session = current.session
        val passive = action in setOf(CodeEditorAction.LOG, CodeEditorAction.HELP,
            CodeEditorAction.KEYBOARD_CONFIG, CodeEditorAction.RULE_HELP, CodeEditorAction.RSS_HELP,
            CodeEditorAction.JS_HELP, CodeEditorAction.REGEX_HELP)
        if (passive) { check(!closed && !current.busy); onPlatformAction(action); return }
        val engine = engine()
        val writable = session?.writable == true
        if (action in setOf(CodeEditorAction.SAVE, CodeEditorAction.DEBUG, CodeEditorAction.LOGIN,
                CodeEditorAction.REPLACE, CodeEditorAction.REPLACE_ALL, CodeEditorAction.FORMAT,
                CodeEditorAction.UNDO, CodeEditorAction.REDO)) check(writable) { "Read-only editor" }
        if (action == CodeEditorAction.DEBUG) check(session?.showDebugSource == true)
        if (action == CodeEditorAction.LOGIN) check(session?.showLoginSource == true)
        if (action == CodeEditorAction.SYNTAX) check(session?.checkJavaScriptSyntax == true)
        val sora = engine as? SoraCodeEditorEngine
        if (action in setOf(CodeEditorAction.SEARCH, CodeEditorAction.PREVIOUS, CodeEditorAction.NEXT,
                CodeEditorAction.REPLACE, CodeEditorAction.REPLACE_ALL, CodeEditorAction.SELECT_ALL,
                CodeEditorAction.FORMAT, CodeEditorAction.SYNTAX, CodeEditorAction.THEME,
                CodeEditorAction.SETTINGS, CodeEditorAction.WRAP)) check(sora != null) { "Unavailable in safe editor" }
        when (action) {
            CodeEditorAction.SAVE -> snapshotThen { model.save() }
            CodeEditorAction.DEBUG -> snapshotThen { model.save(CodeEditActivity.RESULT_ACTION_DEBUG_SOURCE) }
            CodeEditorAction.LOGIN -> snapshotThen { model.save(CodeEditActivity.RESULT_ACTION_LOGIN_SOURCE) }
            CodeEditorAction.SEARCH -> {
                model.search(checkNotNull(owner), checkNotNull(session).search.copy(visible = !session.search.visible))
                if (session.search.visible) engine.focus()
            }
            CodeEditorAction.PREVIOUS -> sora!!.previous()
            CodeEditorAction.NEXT -> sora!!.next()
            CodeEditorAction.REPLACE -> sora!!.replaceCurrent(session!!.search.replacement)
            CodeEditorAction.REPLACE_ALL -> sora!!.replaceAll(session!!.search.replacement)
            CodeEditorAction.SELECT_ALL -> sora!!.selectAll()
            CodeEditorAction.FORMAT -> sora!!.format()
            CodeEditorAction.SYNTAX -> sora!!.syntax()
            CodeEditorAction.UNDO -> engine.undo()
            CodeEditorAction.REDO -> engine.redo()
            CodeEditorAction.WRAP -> { onPlatformAction(action); mutable.value = mutable.value.copy(autoWrap = AppConfig.editAutoWrap) }
            else -> onPlatformAction(action)
        }
    }

    fun search(search: CodeEditorSearch) {
        val engine = engine()
        check(engine is SoraCodeEditorEngine) { "Unavailable in safe editor" }
        check(model.state.value.session?.writable == true || !search.replaceVisible) { "Read-only editor" }
        model.search(checkNotNull(owner), search)
        if (!search.visible) engine.focus()
    }
    fun insert(text: String) { check(model.state.value.session?.writable == true) { "Read-only editor" }; engine().insert(text) }
    fun exit() {
        if (closed) return
        val current = model.state.value
        val engine = controller.engine.takeIf { controller.owner == current.engineOwner }
        if (engine?.dismissActions() == true) return
        when {
            current.session == null -> onClose()
            state.value.status.reading -> { engine?.cancelRead(); model.requestExit() }
            state.value.status.ready && !state.value.status.replacing && !current.busy -> snapshotThen(model::requestExit)
            !current.busy && !state.value.status.replacing -> model.requestExit()
        }
    }
    fun retry() = model.retry()
    fun restart() { check(!model.state.value.busy); owner?.let(model::restartEngine) }
    fun keepEditing() { model.keepEditing(); controller.engine?.restoreEditing() }
    fun discard() { check(model.state.value.confirmDiscard && !model.state.value.busy); model.discard() }

    override fun close() {
        if (closed) return
        closed = true
        modelObservation.cancel(); returns.cancel()
        lifecycle.removeObserver(pauseObserver)
        surfaces.toList().forEach { it.close() }
        controller.exit = null
    }
}

/** A platform view owns one engine/language pair, and cannot edit a replacement owner's session. */
internal class CodeEditorPlatformSurface(
    context: Context,
    private val model: CodeEditorComposeViewModel,
    private val controller: CodeEditorController,
    private val owner: String,
    session: CodeEditorSession,
    safe: Boolean,
    scope: CoroutineScope,
    private val onStatus: (CodeEditorEngineStatus) -> Unit,
    private val onDispose: (CodeEditorPlatformSurface) -> Unit,
) : AutoCloseable {
    val view = FrameLayout(context)
    private var engine: CodeEditorEngine? = null
    private var language: CodeEditorLanguageEngine? = if (safe) null else CodeEditorLanguageEngine(context.applicationContext)
    private var disposed = false
    private var inputEnabled = false
    private val preparation: Job
    private fun active() = !disposed && model.state.value.engineOwner == owner
    init {
        preparation = scope.launch {
            try {
                language?.prepare(session)
                ensureActive()
                if (!active()) return@launch
                val changed: (CodeEditorSnapshot) -> Unit = { snapshot ->
                    if (active()) model.updateEditor(owner, snapshot.text, snapshot.selection.start,
                        snapshot.selection.end, snapshot.programmatic)
                }
                val status: (CodeEditorEngineStatus) -> Unit = { status ->
                    if (active()) { onStatus(status); model.editorReady(owner, status.ready) }
                }
                val created = if (safe) SafeCodeEditorEngine(context, session, changed, status, ::active)
                    else SoraCodeEditorEngine(context, session, checkNotNull(language), changed, status, ::active)
                engine = created
                controller.engine = created
                controller.owner = owner
                view.addView(created.view, FrameLayout.LayoutParams(-1, -1))
                created.setInputEnabled(inputEnabled)
                (created as? SoraCodeEditorEngine)?.search(model.state.value.session?.search ?: session.search)
                created.view.doOnLayout { if (active()) created.focus() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (active()) onStatus(CodeEditorEngineStatus(failed = true)) }
            finally { if (disposed) { language?.dispose(); language = null } }
        }
    }
    fun update(state: CodeEditorComposeState) {
        inputEnabled = !state.busy && state.session?.returnReceipt == null && state.session?.finished == false
        engine?.setInputEnabled(inputEnabled)
        (engine as? SoraCodeEditorEngine)?.search(state.session?.search ?: CodeEditorSearch())
    }
    override fun close() {
        if (disposed) return
        disposed = true
        preparation.cancel()
        engine?.dispose()
        if (controller.engine === engine && controller.owner == owner) { controller.engine = null; controller.owner = null }
        engine = null
        if (preparation.isCompleted) { language?.dispose(); language = null }
        view.removeAllViews()
        onDispose(this)
    }
}
