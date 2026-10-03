package io.legado.app.ui.code

import android.view.View

internal data class CodeEditorSnapshot(
    val text: String,
    val selection: CodeEditorSelection,
    val programmatic: Boolean = false,
)

internal data class CodeEditorEngineStatus(
    val ready: Boolean = false,
    val reading: Boolean = false,
    val failed: Boolean = false,
    val searchResult: String = "0",
    val replacing: Boolean = false,
    val replacementError: String? = null,
)

/** Only the native editing surface is bridged. Toolbars, search and keyboard help are Compose. */
internal interface CodeEditorEngine {
    val view: View
    val safe: Boolean

    fun snapshot(onResult: (CodeEditorSnapshot) -> Unit)

    fun cancelRead(restoreEditing: Boolean = true)

    fun restoreEditing()

    fun insert(text: String, onResult: (Boolean) -> Unit = {})

    fun undo()

    fun redo()

    fun dismissActions(): Boolean

    fun focus()

    fun dispose()
}
