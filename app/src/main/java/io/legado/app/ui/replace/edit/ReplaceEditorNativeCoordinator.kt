package io.legado.app.ui.replace.edit

import android.content.Context
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.widget.code.EditSafety
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The UI owns no repository, durable draft or external result. Every binding has one owner. */
internal class ReplaceEditorNativeCoordinator(
    private val context: Context,
    private val model: ReplaceEditorViewModel,
    assist: ReplaceEditorAssistRepository,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val launchEditor: (ReplaceEditorLaunch, Int) -> Unit,
    private val finish: (Boolean) -> Unit,
    private val copied: (String) -> Unit,
    private val clipboard: () -> String?,
    private val help: () -> Unit,
    private val config: () -> Unit,
    private val notice: (String) -> Unit,
) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private var owner: Any? = null
    private var closed = false
    private var finished = false
    private var revision = 0L
    private var previous: Map<String, Any?>? = null
    private val rejected = mutableSetOf<String>()
    private var keys: List<ReplaceEditorAssist> = emptyList()
    private var rows = 1
    private val updates = MutableStateFlow(0L)
    val changes: Flow<Any> = merge(model.state.map { it as Any }, updates.map { it as Any })
    private val jobs = mutableListOf<Job>()
    init {
        jobs += scope.launch { assist.keys().collect { keys = it; updates.value++ } }
        jobs += scope.launch { assist.rows().collect { rows = it; updates.value++ } }
        jobs += scope.launch {
            combine(model.state, lifecycle.currentStateFlow, updates) { state, _, _ -> state }
                .collect { state ->
                    if (!canDeliver()) return@collect
                    if (state.finished) {
                        if (!finished) { finished = true; finish(state.saved) }
                    } else {
                        if (state.error != null || state.truncated || state.focusRequired) {
                            model.clearNotice()
                            notice(state.error ?: if (state.focusRequired) context.getString(R.string.please_focus_cursor_on_textbox) else context.getString(R.string.replace_preview_truncated, io.legado.app.model.replace.ReplacePreview.MAX_SAMPLE_LENGTH))
                        }
                        state.editor?.takeIf { state.editorLaunch }?.let {
                            model.editorLaunched(it.nonce)
                            try { launchEditor(it, state.draft[it.field].start) }
                            catch (error: Exception) {
                                model.editorResult(it.nonce, null, null, null, false)
                                notice(error.localizedMessage ?: "Error")
                            }
                        }
                    }
                }
        }
    }
    private fun canDeliver() = !closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()
    fun onHostReady() { updates.value++ }
    fun requestBack() { if (!closed) model.close(forceDirty = rejected.isNotEmpty()) }
    fun bind(): Any = Any().also { check(!closed); owner = it }
    fun detach(binding: Any) { if (owner === binding) owner = null }
    private fun checkOwner(binding: Any) { check(!closed && owner === binding) }
    fun snapshot(binding: Any): Map<String, Any?> {
        checkOwner(binding)
        val state = model.state.value
        val fields = ReplaceEditorField.entries.associate { field ->
            val value = state.draft[field]
            val unsafe = field != ReplaceEditorField.Sample &&
                (EditSafety.isTooLongForInline(value.text) || EditSafety.isCombiningHeavy(value.text))
            field.name to mapOf("text" to if (unsafe) "" else value.text,
                "start" to if (unsafe) 0 else value.start, "end" to if (unsafe) 0 else value.end,
                "external" to unsafe, "label" to context.getString(field.label()))
        }
        val projection = mapOf<String, Any?>(
            "labels" to mapOf(
                "undo" to R.string.replace_flutter_undo, "redo" to R.string.replace_flutter_redo,
                "heading" to R.string.replace_rule_edit, "back" to R.string.back,
                "save" to R.string.action_save, "editor" to R.string.edit_content,
                "more" to R.string.more_menu, "copy" to R.string.copy_rule,
                "paste" to R.string.paste_rule, "retry" to R.string.retry,
                "cancel" to R.string.cancel, "keep" to R.string.yes,
                "discard" to R.string.no, "exit" to R.string.exit,
                "exitMessage" to R.string.exit_no_save, "regex" to R.string.use_regex,
                "title" to R.string.scope_title, "source" to R.string.scope_source,
                "content" to R.string.scope_content, "help" to R.string.help,
                "config" to R.string.assists_key_config, "output" to R.string.replace_preview_output,
                "Timeout" to R.string.replace_preview_timeout,
                "ContextUnavailable" to R.string.replace_preview_context_unavailable,
                "JsEvaluation" to R.string.replace_preview_js_error,
                "previewError" to R.string.replace_preview_error,
            ).mapValues { context.getString(it.value) },
            "fields" to fields, "focus" to state.focus?.name, "loaded" to state.loaded,
            "busy" to state.busy, "editable" to state.editable, "finished" to state.finished,
            "exit" to state.exit, "editorOpen" to (state.editor != null), "editorReturning" to state.editorReturning,
            "regex" to state.draft.regex, "title" to state.draft.title,
            "source" to state.draft.source, "content" to state.draft.content,
            "preview" to state.preview, "previewFailed" to state.previewFailed,
            "previewError" to state.previewError?.name, "scroll" to state.scroll,
            "keys" to keys.map { mapOf("key" to it.key, "value" to it.value) }, "rows" to rows)
        if (projection != previous) { previous = projection; revision++ }
        return projection + mapOf("generation" to generation, "revision" to revision)
    }
    fun command(binding: Any, arguments: Any?): Map<String, Any?> {
        checkOwner(binding)
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val args = arguments as? Map<*, *> ?: error("Invalid command")
        require(args["generation"] == generation)
        val op = args["op"] as? String ?: error("Missing operation")
        if (op == "field") {
            val key = args["key"] as? String ?: error("Missing field")
            try {
                check(model.state.value.editable)
                val field = ReplaceEditorField.valueOf(key)
                val text = args["text"] as? String ?: error("Missing text")
                val start = args["start"] as? Int ?: error("Missing selection")
                val end = args["end"] as? Int ?: error("Missing selection")
                require(start in 0..text.length && end in 0..text.length)
                require(field == ReplaceEditorField.Sample || (!EditSafety.isTooLongForInline(text) && !EditSafety.isCombiningHeavy(text)))
                model.field(field, ReplaceEditorText(text, start, end))
                rejected.remove(key)
            } catch (error: Exception) { rejected.add(key); throw error }
        } else {
            if (op !in setOf("close", "keep", "discard")) check(rejected.isEmpty())
            fun field() = ReplaceEditorField.valueOf(args["key"] as String)
            when (op) {
                "focus" -> model.focus(field())
                "clearFocus" -> model.clearFocus()
                "flags" -> model.flags(args["regex"] as Boolean, args["title"] as Boolean, args["source"] as Boolean, args["content"] as Boolean)
                "save" -> model.save()
                "close" -> model.close(forceDirty = rejected.isNotEmpty() || args["localDirty"] == true)
                "keep" -> model.keepEditing()
                "discard" -> model.discard()
                "editor" -> model.openEditor((args["key"] as? String)?.let(ReplaceEditorField::valueOf))
                "copy" -> { check(canDeliver()); scope.launch {
                    val text = model.copyJson(); currentCoroutineContext().ensureActive()
                    if (text != null && owner === binding && canDeliver()) copied(text)
                } }
                "paste" -> { check(canDeliver()); model.paste(clipboard().orEmpty()) }
                "help" -> { check(canDeliver()); help() }
                "config" -> { check(canDeliver()); config() }
                "insert" -> model.insert(args["text"] as String)
                "undo" -> model.undo()
                "redo" -> model.redo()
                "retry" -> model.load()
                "scroll" -> model.scroll((args["offset"] as Int).coerceAtLeast(0))
                "retryEditor" -> model.retryEditor()
                "discardEditor" -> model.discardEditor()
                else -> error("Unknown operation")
            }
        }
        return snapshot(binding)
    }
    override fun close() { closed = true; owner = null; jobs.forEach(Job::cancel) }
    private companion object { val generations = AtomicInteger() }
}
