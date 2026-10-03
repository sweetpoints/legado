package io.legado.app.ui.code

import android.content.Intent
import androidx.lifecycle.SavedStateHandle

private val codeEditorLaunchKeys =
    listOf(
        "cacheKey",
        "textFile",
        "text",
        "readOnly",
        "cursorPosition",
        "title",
        "languageName",
        CodeEditActivity.EXTRA_CHECK_JAVASCRIPT_SYNTAX,
        CodeEditActivity.EXTRA_SHOW_DEBUG_SOURCE,
        CodeEditActivity.EXTRA_SHOW_LOGIN_SOURCE,
        "returnUnchangedText",
        "useTextFile",
    )

/**
 * Android injects Intent extras as default SavedStateHandle values, even without explicit writes.
 */
internal fun clearCodeEditorLaunchDefaults(savedState: SavedStateHandle) {
    // This VM persists every editing value in its private session. Default arguments,
    // including unknown caller extras, therefore have no SavedState ownership here.
    savedState.keys().filter { it != "codeEditorSessionId" }.forEach { savedState.remove<Any>(it) }
}

internal fun clearCodeEditorLaunchIntent(intent: Intent) {
    codeEditorLaunchKeys.forEach(intent::removeExtra)
}
