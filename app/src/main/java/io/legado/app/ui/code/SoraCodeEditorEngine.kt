package io.legado.app.ui.code

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.view.isVisible
import io.github.rosemoe.sora.event.ColorSchemeUpdateEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.Cursor
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.github.rosemoe.sora.widget.LegadoCodeSearchSnapshot
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.share
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sora remains the text engine, including its completion, snippets, selection and undo history. */
internal class SoraCodeEditorEngine(
    private val context: Context,
    private val session: CodeEditorSession,
    private val languageEngine: CodeEditorLanguageEngine,
    private val onChanged: (CodeEditorSnapshot) -> Unit,
    private val onStatus: (CodeEditorEngineStatus) -> Unit,
    private val isCurrentOwner: () -> Boolean = { true },
    private val calculateReplacement: suspend (CodeEditorReplacement, String) -> String =
        ::replaceCodeEditorMatches,
) : CodeEditorEngine {
    override val safe = false
    override val view = CodeEditor(context)
    private val language = languageEngine.attachLanguage()
    private val searcher = view.searcher
    private var disposed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var contentRevision = 0L
    private var searchRevision = 0L
    private var cachedText = session.text
    private var cachedRevision = 0L
    private var replacing = false
    val isReplacing: Boolean
        get() = replacing

    private var replacementError: String? = null
    private val active: Boolean
        get() = !disposed && isCurrentOwner()

    private var textActions: CodeTextActions? = null
    private var searchConfig: Triple<Boolean, String, Boolean>? = null

    init {
        view.apply {
            id = R.id.editText
            isSaveEnabled = false
            colorScheme = TextMateColorScheme2.create(ThemeRegistry.getInstance())
            nonPrintablePaintingFlags = AppConfig.editNonPrintable
            setTextSize(AppConfig.editFontScale.toFloat())
            isWordwrap = AppConfig.editAutoWrap
            props.maxIPCTextLength = 64 * 1024
            setEditorLanguage(language)
            setText(session.text)
            editable = session.writable
            val bounded = session.selection.bounded(session.text)
            val left = cursor.indexer.getCharPosition(minOf(bounded.start, bounded.end))
            val right = cursor.indexer.getCharPosition(maxOf(bounded.start, bounded.end))
            setSelectionRegion(left.line, left.column, right.line, right.column)
            cursor.selectionDirection =
                if (bounded.start > bounded.end) Cursor.DIRECTION_RTL else Cursor.DIRECTION_LTR
            requestFocus()
        }
        setupTextActions()
        view.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            contentRevision++
            rememberSnapshot()
        }
        view.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            rememberSnapshot()
            publishStatus()
        }
        view.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> publishStatus() }
        search(session.search)
        publishStatus()
    }

    private fun rememberSnapshot() {
        // Sora forbids content mutations while dispatching a text event. Posting also records
        // the completed transaction's cursor, and the VM rejects a retired engine owner.
        view.postInLifecycle {
            if (active) snapshot(onChanged)
        }
    }

    override fun snapshot(onResult: (CodeEditorSnapshot) -> Unit) {
        if (!active) return
        if (cachedRevision != contentRevision) {
            cachedText = view.text.toString()
            cachedRevision = contentRevision
        }
        val cursor = view.cursor
        val selection =
            if (cursor.selectionDirection == Cursor.DIRECTION_RTL)
                CodeEditorSelection(cursor.right, cursor.left)
            else CodeEditorSelection(cursor.left, cursor.right)
        onResult(CodeEditorSnapshot(cachedText, selection, programmatic = !session.writable))
    }

    private fun publishStatus() {
        if (!active) return
        val total = if (searcher.hasQuery()) searcher.matchedPositionCount else 0
        val current = if (searcher.hasQuery()) searcher.currentMatchedPositionIndex + 1 else 0
        onStatus(
            CodeEditorEngineStatus(
                ready = true,
                replacing = replacing,
                replacementError = replacementError,
                searchResult = if (current > 0) "$current/$total" else "$total",
            )
        )
    }

    fun search(search: CodeEditorSearch) {
        val config = Triple(search.visible, search.query, search.regex)
        if (!active || searchConfig == config) return
        searchRevision++
        searchConfig = config
        if (!search.visible || search.query.isEmpty()) {
            searcher.stopSearch()
            view.invalidate()
            publishStatus()
            return
        }
        try {
            searcher.search(
                search.query,
                SearchOptions(
                    if (search.regex) SearchOptions.TYPE_REGULAR_EXPRESSION
                    else SearchOptions.TYPE_NORMAL,
                    !search.regex,
                    RegexBackrefGrammar.DEFAULT,
                ),
            )
        } catch (_: PatternSyntaxException) {
            searcher.stopSearch()
            view.invalidate()
        }
        publishStatus()
    }

    fun previous() {
        if (active && searcher.hasQuery()) searcher.gotoPrevious()
    }

    fun next() {
        if (active && searcher.hasQuery()) searcher.gotoNext()
    }

    fun replaceCurrent(replacement: String) {
        if (active && searcher.hasQuery()) searcher.replaceCurrentMatch(replacement)
    }

    fun replaceAll(replacement: String) {
        if (!active || replacing || !view.isEditable) return
        val acceptedSearch = LegadoCodeSearchSnapshot.capture(view) ?: return
        val capturedContentRevision = contentRevision
        val capturedSearchRevision = searchRevision
        val request =
            CodeEditorReplacement(
                source = acceptedSearch.source,
                query = acceptedSearch.pattern,
                regex = acceptedSearch.type == SearchOptions.TYPE_REGULAR_EXPRESSION,
                caseInsensitive = acceptedSearch.caseInsensitive,
                grammar = acceptedSearch.grammar,
                preserveCase = acceptedSearch.preserveCase,
                matches = acceptedSearch.regions.map { CodeEditorMatch(it.start, it.end) },
            )
        replacing = true
        replacementError = null
        view.editable = false
        publishStatus()
        scope.launch {
            try {
                val replaced =
                    withContext(Dispatchers.Default) {
                        calculateReplacement(request, replacement)
                    }
                // Text equality alone cannot reject edit -> Undo or a retired session. Both
                // revisions and the fixed native owner must still authorize this transaction.
                if (
                    !active ||
                        contentRevision != capturedContentRevision ||
                        searchRevision != capturedSearchRevision ||
                        view.text.toString() != request.source
                )
                    return@launch
                val cursor = view.cursor.left()
                val content = view.text
                content.replace(
                    0,
                    0,
                    content.lineCount - 1,
                    content.getColumnCount(content.lineCount - 1),
                    replaced,
                )
                // setSelectionAround is protected in pinned Sora. Apply its public-API clamp
                // so the original cursor remains as close as the new document permits.
                val line = cursor.line.coerceAtMost(content.lineCount - 1)
                val column =
                    if (line == cursor.line)
                        cursor.column.coerceAtMost(content.getColumnCount(line))
                    else content.getColumnCount(line)
                view.setSelection(line, column)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (active) replacementError = failure.localizedMessage ?: failure.toString()
            } finally {
                replacing = false
                if (active) {
                    view.editable = session.writable
                    publishStatus()
                }
            }
        }
    }

    fun selectAll() {
        if (active) view.selectAll()
    }

    fun selectedText(): String =
        if (active && view.cursor.isSelected)
            view.text.substring(view.cursor.left, view.cursor.right)
        else ""

    fun format() {
        val revision = contentRevision
        if (active) languageEngine.formatCode(view) { active && contentRevision == revision }
    }

    fun syntax() {
        val revision = contentRevision
        if (active)
            languageEngine.checkJavaScriptSyntax(view) { active && contentRevision == revision }
    }

    fun theme(index: Int) {
        if (active) languageEngine.theme(index, view) { active }
    }

    fun settings(fontSize: Int?, autoComplete: Boolean?, autoWrap: Boolean?, nonPrintable: Int?) {
        if (!active) return
        fontSize?.let { view.setTextSize(it.toFloat()) }
        autoComplete?.let {
            language.isAutoCompleteEnabled = it
            view.setEditorLanguage(language)
        }
        autoWrap?.let { view.isWordwrap = it }
        nonPrintable?.let { view.nonPrintablePaintingFlags = it }
    }

    private fun setupTextActions() {
        val actions = view.getComponent(EditorTextActionWindow::class.java)
        val copy =
            actions.view.findViewById<ImageButton>(io.github.rosemoe.sora.R.id.panel_btn_copy)
        val buttons = copy.parent as ViewGroup
        val shareButton =
            ImageButton(context).apply {
                id = R.id.code_share_selection
                contentDescription = context.getString(R.string.share)
                setImageResource(R.drawable.ic_share)
                background = copy.background?.constantState?.newDrawable()?.mutate()
                setPadding(copy.paddingLeft, copy.paddingTop, copy.paddingRight, copy.paddingBottom)
                layoutParams = LinearLayout.LayoutParams(copy.layoutParams)
                setOnClickListener {
                    val cursor = view.cursor
                    if (active && cursor.isSelected) {
                        context.share(view.text.subSequence(cursor.left, cursor.right).toString())
                        actions.dismiss()
                    }
                }
            }
        buttons.addView(shareButton, buttons.indexOfChild(copy) + 1)
        fun updateShareButton() {
            shareButton.isVisible = view.cursor.isSelected
            shareButton.setColorFilter(
                view.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR)
            )
        }
        updateShareButton()
        view.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            if (active) updateShareButton()
        }
        view.subscribeEvent(ColorSchemeUpdateEvent::class.java) { _, _ ->
            if (active) updateShareButton()
        }
        textActions = CodeTextActions(view)
    }

    override fun cancelRead(restoreEditing: Boolean) = Unit

    override fun restoreEditing() = Unit

    override fun setInputEnabled(enabled: Boolean) {
        if (active) view.editable = enabled && session.writable && !replacing
    }

    override fun insert(text: String, onResult: (Boolean) -> Unit) {
        if (!active || !view.isEditable) onResult(false)
        else {
            view.insertText(text, text.length)
            snapshot(onChanged)
            onResult(true)
        }
    }

    override fun undo() {
        if (active) view.undo()
    }

    override fun redo() {
        if (active) view.redo()
    }

    override fun dismissActions() = textActions?.dismiss() == true

    override fun focus() {
        if (active) view.requestFocus()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        textActions?.dismiss()
        searcher.stopSearch()
        view.release()
        languageEngine.dispose()
    }
}
