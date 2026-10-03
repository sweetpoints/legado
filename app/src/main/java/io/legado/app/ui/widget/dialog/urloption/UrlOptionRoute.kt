package io.legado.app.ui.widget.dialog.urloption

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun UrlOptionRoute(
    charsets: List<String>,
    onSuccess: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialDraft: UrlOptionDraft = UrlOptionDraft(),
    onDraftChanged: (UrlOptionDraft) -> Unit = {},
) {
    var draft by
        rememberSaveable(stateSaver = UrlOptionDraft.Saver) { mutableStateOf(initialDraft) }
    var finished by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(finished) { if (finished) onClose() }
    UrlOptionScreen(
        draft,
        charsets,
        finished,
        onInput = { field, value ->
            if (!finished) {
                draft = draft.update(field, value)
                onDraftChanged(draft)
            }
        },
        onWebView = {
            if (!finished) {
                draft = draft.copy(webView = it)
                onDraftChanged(draft)
            }
        },
        onConfirm = {
            if (!finished) {
                val json = draft.toJson()
                finished = true
                onSuccess(json)
            }
        },
        onClose = { finished = true },
        modifier = modifier,
    )
}
