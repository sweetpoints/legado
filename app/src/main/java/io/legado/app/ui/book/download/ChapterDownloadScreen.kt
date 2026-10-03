package io.legado.app.ui.book.download

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.download.ChapterDownloadMode

@Composable fun ChapterDownloadScreen(state:ChapterDownloadState,onStart:(String)->Unit,onEnd:(String)->Unit,
    confirm:()->Unit,cancel:()->Unit,retry:()->Unit,modifier:Modifier=Modifier) {
    Surface(modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.8f),shape=MaterialTheme.shapes.extraLarge) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(if(state.mode==ChapterDownloadMode.Audio)R.string.audio_cache_range else R.string.offline_cache),style=MaterialTheme.typography.headlineSmall)
            if(!state.loaded || state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.loaded)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.start,onStart,enabled=!state.busy && state.pending==null,label={Text(stringResource(R.string.start))},
                    singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f).testTag("chapter-download-start"))
                OutlinedTextField(state.end,onEnd,enabled=!state.busy && state.pending==null,label={Text(stringResource(R.string.end))},
                    singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f).testTag("chapter-download-end"))
            }
            if(state.invalidRange)Text(stringResource(R.string.error_scope_input),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("chapter-download-invalid"))
            state.error?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton(retry,enabled=!state.busy,modifier=Modifier.testTag("chapter-download-retry")){Text(stringResource(R.string.retry))}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(cancel,enabled=!state.busy,modifier=Modifier.testTag("chapter-download-cancel")){Text(stringResource(R.string.cancel))}
                TextButton(confirm,enabled=state.loaded && !state.busy && state.pending==null && !state.finished,modifier=Modifier.testTag("chapter-download-confirm")){Text(stringResource(R.string.confirm))}
            }
        }
    }
}
