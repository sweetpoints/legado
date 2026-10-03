package io.legado.app.ui.code

import androidx.annotation.Keep

/** Legacy launch values are consumed once on IO, then only the private session UUID is saved. */
internal data class CodeEditorLaunch(
    val cacheKey: String? = null,
    val textFile: String? = null,
    val text: String? = null,
    val readOnly: Boolean = false,
    val cursorPosition: Int = 0,
    val title: String? = null,
    val languageName: String? = null,
    val checkJavaScriptSyntax: Boolean = false,
    val showDebugSource: Boolean = false,
    val showLoginSource: Boolean = false,
    val returnUnchangedText: Boolean = false,
    val useTextFile: Boolean = false,
)

@Keep
internal data class CodeEditorSelection(val start: Int = 0, val end: Int = start) {
    fun bounded(text: String) =
        copy(start = start.coerceIn(0, text.length), end = end.coerceIn(0, text.length))
}

@Keep
internal data class CodeEditorSearch(
    val visible: Boolean = false,
    val replaceVisible: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val regex: Boolean = true,
    val ignoreCase: Boolean = false,
    val querySelection: CodeEditorSelection = CodeEditorSelection(),
    val replacementSelection: CodeEditorSelection = CodeEditorSelection(),
)

@Keep
internal data class CodeEditorReturnReceipt(
    val id: String,
    val cursorPosition: Int,
    val includeText: Boolean,
    val action: String? = null,
    val textFile: String? = null,
    val claimed: Boolean = false,
    val prepared: Boolean = false,
)

@Keep
internal data class CodeEditorSession(
    val initialText: String,
    val text: String = initialText,
    val selection: CodeEditorSelection = CodeEditorSelection(),
    val title: String? = null,
    val languageName: String = "source.js",
    val writable: Boolean = true,
    val checkJavaScriptSyntax: Boolean = false,
    val showDebugSource: Boolean = false,
    val showLoginSource: Boolean = false,
    val returnUnchangedText: Boolean = false,
    val useTextFile: Boolean = false,
    val search: CodeEditorSearch = CodeEditorSearch(),
    val returnReceipt: CodeEditorReturnReceipt? = null,
    val revision: Long = 0,
    val finished: Boolean = false,
) {
    val dirty: Boolean
        get() = text != initialText

    fun edited(value: String, selection: CodeEditorSelection): CodeEditorSession =
        copy(text = value, selection = selection.bounded(value))

    fun closed(): CodeEditorSession =
        copy(
            initialText = "",
            text = "",
            selection = CodeEditorSelection(),
            search = CodeEditorSearch(),
            returnReceipt = null,
            finished = true,
        )
}

internal fun codeEditorLanguage(text: String, requested: String?): String {
    val trimmed = text.trim()
    val html = Regex("""^(?:\[[\s\d.]])?<(?:html|!DOCTYPE)""", RegexOption.IGNORE_CASE)
    return if (html.containsMatchIn(trimmed) && trimmed.endsWith(">")) "text.html.basic"
    else requested ?: "source.js"
}

/** Prepared on IO from a durably accepted snapshot, never stored in Android SavedState. */
internal data class CodeEditorResultPayload(
    val cursorPosition: Int,
    val text: String? = null,
    val textFile: String? = null,
    val action: String? = null,
)

internal class CodeEditorSessionConflict : IllegalStateException("代码草稿已由其他编辑会话更新，请点击重试读取已保存的草稿")
