package io.legado.app.ui.code

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.toastOnUi

/** A native text engine only; page controls and private state belong to Compose and its VM. */
internal class SafeCodeEditorEngine(
    private val context: Context,
    private val session: CodeEditorSession,
    private val onChanged: (CodeEditorSnapshot) -> Unit,
    private val onStatus: (CodeEditorEngineStatus) -> Unit,
    private val isCurrentOwner: () -> Boolean = { true },
) : CodeEditorEngine {
    override val safe = true
    override val view = WebView(context)
    private var status = CodeEditorEngineStatus()
    private var disposed = false
    private var destroyed = false
    private val active: Boolean
        get() = !disposed && isCurrentOwner()

    private var readGeneration = 0
    private var loadTimeout: Runnable? = null
    private var readTimeout: Runnable? = null
    private var draft =
        SafeEditorContent(
            session.text,
            minOf(session.selection.start, session.selection.end),
            session.dirty,
        )

    init {
        load()
    }

    private fun publish(updated: CodeEditorEngineStatus) {
        status = updated
        if (active) onStatus(updated)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load() {
        view.isSaveEnabled = false
        view.contentDescription = context.getString(R.string.safe_code_editor)
        view.setBackgroundColor(context.backgroundColor)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            blockNetworkLoads = true
        }
        // The page is locally generated, network-disabled, and receives text only as base64.
        view.addJavascriptInterface(
            object {
                @Keep
                @JavascriptInterface
                fun changed(text: String, cursor: Int, dirty: Boolean) {
                    view.post {
                        if (active && !destroyed) {
                            draft =
                                SafeEditorContent(text, cursor, dirty)
                                    .resolveAgainst(session.initialText)
                            onChanged(
                                CodeEditorSnapshot(
                                    draft.text,
                                    CodeEditorSelection(draft.cursorPosition),
                                )
                            )
                        }
                    }
                }

                @Keep
                @JavascriptInterface
                fun selected(cursor: Int) {
                    view.post {
                        if (active && !destroyed) {
                            draft =
                                if (draft.dirty)
                                    draft.copy(
                                        cursorPosition = cursor.coerceIn(0, draft.text.length)
                                    )
                                else
                                    SafeEditorContent(draft.text, cursor, false)
                                        .resolveAgainst(session.initialText)
                            onChanged(
                                CodeEditorSnapshot(
                                    draft.text,
                                    CodeEditorSelection(draft.cursorPosition),
                                )
                            )
                        }
                    }
                }
            },
            "EditorDraft",
        )
        view.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (!active || destroyed) return
                    loadTimeout?.let(view::removeCallbacks)
                    loadTimeout = null
                    publish(status.copy(ready = true, failed = false))
                    view.requestFocus()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (active && request.isForMainFrame) markFailed()
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    if (!active) return true
                    cancelRead(restoreEditing = false)
                    loadTimeout?.let(view::removeCallbacks)
                    loadTimeout = null
                    destroyed = true
                    view.visibility = View.GONE
                    view.destroy()
                    publish(CodeEditorEngineStatus(failed = true))
                    context.toastOnUi(R.string.safe_code_editor_load_failed)
                    return true
                }
            }
        loadTimeout =
            Runnable { if (active && !status.ready) markFailed() }
                .also { view.postDelayed(it, LOAD_TIMEOUT_MILLIS) }
        view.loadDataWithBaseURL(
            null,
            buildSafeEditorHtml(session.text),
            "text/html",
            "utf-8",
            null,
        )
        publish(status)
    }

    private fun markFailed() {
        if (status.failed || !active) return
        loadTimeout?.let(view::removeCallbacks)
        loadTimeout = null
        publish(status.copy(ready = false, failed = true))
        context.toastOnUi(R.string.safe_code_editor_load_failed)
    }

    private fun buildSafeEditorHtml(text: String): String {
        val encodedText =
            Base64.encodeToString(
                text.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
        val background = ColorUtils.intToString(context.backgroundColor)
        val foreground = ColorUtils.intToString(context.primaryTextColor)
        val readOnly = if (session.writable) "" else " readonly"
        val writable = session.writable
        val wrap = if (AppConfig.editAutoWrap) "soft" else "off"
        val cursorPosition =
            text
                .take(
                    minOf(session.selection.start, session.selection.end).coerceIn(0, text.length)
                )
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .length
        return """
            <!doctype html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <meta http-equiv="Content-Security-Policy"
                    content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'" />
                <style>
                    html, body {
                        width: 100%;
                        height: 100%;
                        margin: 0;
                        overflow: hidden;
                        background: $background;
                    }
                    textarea {
                        box-sizing: border-box;
                        width: 100%;
                        height: 100%;
                        border: 0;
                        outline: 0;
                        resize: none;
                        padding: 12px;
                        color: $foreground;
                        background: $background;
                        font-family: monospace;
                        font-size: ${AppConfig.editFontScale}px;
                        line-height: 1.4;
                    }
                </style>
            </head>
            <body>
                <textarea id="code" wrap="$wrap" spellcheck="false" autocomplete="off"
                    autocorrect="off" autocapitalize="off"$readOnly></textarea>
                <script>
                    function decodeBase64(value) {
                        var binary = atob(value);
                        var bytes = new Uint8Array(binary.length);
                        for (var i = 0; i < binary.length; i++) {
                            bytes[i] = binary.charCodeAt(i);
                        }
                        if (window.TextDecoder) {
                            return new TextDecoder("utf-8", { ignoreBOM: true }).decode(bytes);
                        }
                        var escaped = "";
                        for (var j = 0; j < bytes.length; j++) {
                            escaped += "%" + ("00" + bytes[j].toString(16)).slice(-2);
                        }
                        return decodeURIComponent(escaped);
                    }

                    var editor = document.getElementById("code");
                    editor.value = decodeBase64("$encodedText");
                    var initialValue = editor.value;
                    var initialDraftDirty = ${session.dirty};
                    var editorWritable = $writable;
                    var initialCursor = Math.min(editor.value.length, $cursorPosition);
                    editor.setSelectionRange(initialCursor, initialCursor);
                    editor.focus();
                    function rememberDraft() {
                        EditorDraft.changed(editor.value, editor.selectionStart || 0,
                            editor.value !== initialValue || initialDraftDirty);
                    }
                    editor.addEventListener("input", rememberDraft);
                    document.addEventListener("selectionchange", function() {
                        EditorDraft.selected(editor.selectionStart || 0);
                    });
                    rememberDraft();

                    window.__setEditorReadOnly = function(readOnly) {
                        if (readOnly) {
                            editor.blur();
                            editor.readOnly = true;
                        } else if (editorWritable) {
                            editor.readOnly = false;
                            editor.focus();
                        } else {
                            editor.readOnly = true;
                        }
                    };

                    window.__getEditorState = function() {
                        window.__setEditorReadOnly(true);
                        return JSON.stringify({
                            text: editor.value,
                            cursorPosition: editor.selectionStart || 0,
                            dirty: editor.value !== initialValue || initialDraftDirty
                        });
                    };

                    window.__insertEditorText = function(encodedValue) {
                        if (editor.readOnly) return false;
                        var value = decodeBase64(encodedValue);
                        var start = editor.selectionStart || 0;
                        var end = editor.selectionEnd || start;
                        if (editor.setRangeText) {
                            editor.setRangeText(value, start, end, "end");
                        } else {
                            editor.value = editor.value.slice(0, start) + value + editor.value.slice(end);
                            var cursor = start + value.length;
                            editor.setSelectionRange(cursor, cursor);
                        }
                        rememberDraft();
                        return true;
                    };
                </script>
            </body>
            </html>
        """
            .trimIndent()
    }

    override fun snapshot(onResult: (CodeEditorSnapshot) -> Unit) {
        if (!active || destroyed || status.reading || !status.ready) return
        val generation = ++readGeneration
        publish(status.copy(reading = true))
        readTimeout =
            Runnable {
                if (active && readGeneration == generation) {
                    readGeneration++
                    readTimeout = null
                    publish(status.copy(reading = false))
                    restoreEditing()
                    context.toastOnUi(R.string.safe_code_editor_read_failed)
                }
            }
                .also { view.postDelayed(it, READ_TIMEOUT_MILLIS) }
        view.evaluateJavascript("window.__getEditorState && window.__getEditorState();") { value ->
            if (!active || destroyed || readGeneration != generation) return@evaluateJavascript
            readTimeout?.let(view::removeCallbacks)
            readTimeout = null
            publish(status.copy(reading = false))
            val result = SafeEditorResultCodec.decode(value)?.resolveAgainst(session.initialText)
            if (result == null) {
                restoreEditing()
                context.toastOnUi(R.string.safe_code_editor_read_failed)
            } else {
                draft = result
                onResult(
                    CodeEditorSnapshot(result.text, CodeEditorSelection(result.cursorPosition))
                )
            }
        }
    }

    override fun cancelRead(restoreEditing: Boolean) {
        readGeneration++
        readTimeout?.let(view::removeCallbacks)
        readTimeout = null
        publish(status.copy(reading = false))
        if (restoreEditing) restoreEditing()
    }

    override fun restoreEditing() {
        if (!active || destroyed || !status.ready || !session.writable) return
        view.evaluateJavascript(
            "window.__setEditorReadOnly && window.__setEditorReadOnly(false);",
            null,
        )
    }

    override fun insert(text: String, onResult: (Boolean) -> Unit) {
        if (!active || destroyed || !status.ready || status.reading || !session.writable) {
            onResult(false)
            return
        }
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        view.evaluateJavascript(
            "window.__insertEditorText && window.__insertEditorText('$encoded');"
        ) {
            if (active && !destroyed) onResult(it == "true")
        }
    }

    override fun undo() {
        if (canEdit()) view.evaluateJavascript("document.execCommand('undo');", null)
    }

    override fun redo() {
        if (canEdit()) view.evaluateJavascript("document.execCommand('redo');", null)
    }

    private fun canEdit() =
        active && !destroyed && status.ready && !status.reading && session.writable

    override fun dismissActions() = false

    override fun focus() {
        if (active && !destroyed) view.requestFocus()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        cancelRead(restoreEditing = false)
        loadTimeout?.let(view::removeCallbacks)
        loadTimeout = null
        if (!destroyed) {
            view.webViewClient = WebViewClient()
            view.removeJavascriptInterface("EditorDraft")
            view.stopLoading()
            view.loadUrl("about:blank")
            view.clearHistory()
            view.removeAllViews()
            view.destroy()
            destroyed = true
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 15_000L
        const val READ_TIMEOUT_MILLIS = 5_000L
    }
}
