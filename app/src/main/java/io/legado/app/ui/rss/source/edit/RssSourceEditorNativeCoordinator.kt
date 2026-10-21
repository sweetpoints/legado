package io.legado.app.ui.rss.source.edit

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.widget.code.EditSafety
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** View-owned transport lease; the existing ViewModel and repository retain draft/effect authority. */
class RssSourceEditorNativeCoordinator(
    context: Context,
    private val model: RssSourceEditorViewModel,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val canHandle: () -> Boolean,
    private val deliver: (RssSourceEditorEffect, String?) -> Unit,
    private val registerNative: () -> Unit,
    private val finish: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onPaste: () -> Unit,
    private val onKeyboardConfig: () -> Unit,
    assistRepository: RssSourceEditorAssistRepository = RssSourceEditorAssistRepository(),
    private val shareRepository: RssSourceEditorShareRepository = RssSourceEditorShareRepository(context),
) : AutoCloseable {
    val generation = generations.updateAndGet { if (it == Int.MAX_VALUE) 1 else it + 1 }
    private val appContext = context.applicationContext
    private var assists = emptyList<RssSourceEditorAssist>()
    private var preferences = RssSourceEditorAssistPreferences(assistRepository.rows, assistRepository.maxLines)
    private val updates = MutableStateFlow(0L)
    val state: Flow<Unit> = combine(model.state, updates) { _, _ -> Unit }
    private var binding: Any? = null
    private var retired = false
    private var closedHost = false
    private var revision = 0L
    private var previous: Map<String, Any?>? = null
    private val jobs = listOf(
        scope.launch {
            assistRepository.keys().catch { emit(emptyList()) }.collect {
                assists = it; updates.value++
            }
        },
        scope.launch {
            assistRepository.preferences().collect { preferences = it; updates.value++ }
        },
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { runCatching { model.flush() } } }
            }
        },
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                state.collect { dispatch(model.state.value) }
            }
        },
    )

    fun bind(): Any { check(!retired); return Any().also { binding = it } }
    fun detach(owner: Any) { if (binding === owner) binding = null }
    private fun checkOwner(owner: Any) { check(!retired && binding === owner) { "Retired editor binding" } }

    fun snapshot(owner: Any): Map<String, Any?> {
        checkOwner(owner)
        val result = rssSourceEditorSnapshot(model.state.value, generation,
            { field -> field.labelResource()?.let(appContext::getString) ?: field.literalLabel() },
            listOf(appContext.getString(R.string.source_tab_base), appContext.getString(R.string.source_tab_start),
                appContext.getString(R.string.source_tab_list), "WEB_VIEW"),
            appContext.resources.getStringArray(R.array.rss_type).toList(),
            appContext.resources.getStringArray(R.array.layout_type).toList(),
            assists, preferences.rows, preferences.maxLines)
        if (result != previous) { check(revision < Long.MAX_VALUE); revision++; previous = result }
        return result + ("revision" to revision)
    }

    fun command(owner: Any, arguments: Any?): Map<String, Any?>? {
        checkOwner(owner)
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val command = parseRssEditorCommand(arguments, generation)
        val current = model.state.value
        if (command.op !in setOf("retry", "retryEditor", "discardEditor", "cancel", "keepEditing", "discard"))
            check(current.canEdit) { "Editor is unavailable" }
        if (command.op in setOf("insert", "undo", "redo")) {
            current.focus?.let {
                val text = current.draft[it].text
                check(!EditSafety.isTooLongForInline(text) && !EditSafety.isCombiningHeavy(text))
            }
        }
        if (command.op in setOf("save", "native", "editor", "paste", "keyboardConfig"))
            check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canHandle())
        when (command.op) {
            "field" -> {
                val field = requireNotNull(command.field)
                val before = current.draft[field].text
                check(!EditSafety.isTooLongForInline(before) && !EditSafety.isCombiningHeavy(before))
                model.field(field, RssSourceEditorText(requireNotNull(command.text), command.start, command.end))
            }
            "focus" -> model.focus(requireNotNull(command.field))
            "tab" -> model.tab(command.index)
            "expanded" -> model.expanded(command.value as Boolean)
            "autoComplete" -> model.autoComplete(command.value as Boolean)
            "option" -> model.options { draft -> when (command.key) {
                "enabled" -> draft.copy(enabled = command.value as Boolean)
                "singleUrl" -> draft.copy(singleUrl = command.value as Boolean)
                "cookieJar" -> draft.copy(cookieJar = command.value as Boolean)
                "preload" -> draft.copy(preload = command.value as Boolean)
                "enableJs" -> draft.copy(enableJs = command.value as Boolean)
                "loadWithBaseUrl" -> draft.copy(loadWithBaseUrl = command.value as Boolean)
                "showWebLog" -> draft.copy(showWebLog = command.value as Boolean)
                "cacheFirst" -> draft.copy(cacheFirst = command.value as Boolean)
                "type" -> draft.copy(type = command.value as Int)
                "articleStyle" -> draft.copy(articleStyle = command.value as Int)
                else -> error("Unknown option")
            } }
            "save" -> model.save(requireNotNull(command.save))
            "native" -> model.action(requireNotNull(command.native))
            "editor" -> model.openEditor()
            "paste" -> onPaste()
            "clearCookie" -> model.clearCookie()
            "undo" -> model.undo()
            "redo" -> model.redo()
            "insert" -> model.insert(requireNotNull(command.text))
            "cancel" -> requestBack()
            "keepEditing" -> model.keepEditing()
            "discard" -> { check(!current.nativePending && !current.editorPending); model.discard() }
            "retry" -> model.retry()
            "retryEditor" -> model.retryEditorResult()
            "discardEditor" -> model.discardEditorResult()
            "keyboardConfig" -> onKeyboardConfig()
        }
        return if (command.op in setOf("field", "option", "expanded", "autoComplete")) snapshot(owner) else null
    }

    fun requestBack() {
        if (!retired) { if (model.state.value.exit) model.keepEditing() else model.requestExit() }
    }

    private suspend fun dispatch(value: RssSourceEditorState) {
        if (retired || !canHandle()) return
        registerNative()
        if (value.loading || value.busy) return
        if (value.finished) { complete(); return }
        for (effect in value.effects) {
            if (!canHandle() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) break
            try {
                val payload = when (effect.kind) {
                    RssSourceEditorEffectKind.Clipboard, RssSourceEditorEffectKind.ShareText -> model.copyText()
                    RssSourceEditorEffectKind.ShareQr -> shareRepository.qr(model.copyText())
                    RssSourceEditorEffectKind.SavedVariable -> model.variable()
                    else -> null
                }
                currentCoroutineContext().ensureActive()
                if (!canHandle() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) break
                if (effect.kind in setOf(RssSourceEditorEffectKind.ScanQr, RssSourceEditorEffectKind.File, RssSourceEditorEffectKind.UrlOptions)) {
                    model.ownNative(effect)
                    registerNative()
                }
                model.consume(effect)
                if (effect.kind == RssSourceEditorEffectKind.Close || effect.kind == RssSourceEditorEffectKind.SavedClose) complete()
                else deliver(effect, payload)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                model.consume(effect)
                if (effect.kind == RssSourceEditorEffectKind.Editor) model.editorReturned(effect.id, false, null, null, 0)
                model.nativeReturned(effect.id, null)
                onError(rssEditorSafeError(error.localizedMessage) ?: "Error")
            }
        }
    }

    fun onHostReady() {
        // FragmentManager's state-saved gate can reopen without a model change. Re-enter
        // the same sequential collector, never dispatch a second concurrent effect loop.
        if (!retired) updates.value++
    }
    private fun complete() { if (!closedHost) { closedHost = true; finish(model.state.value.savedResult) } }
    override fun close() { if (!retired) { retired = true; binding = null; jobs.forEach { it.cancel() } } }
    private companion object { val generations = AtomicInteger() }
}
