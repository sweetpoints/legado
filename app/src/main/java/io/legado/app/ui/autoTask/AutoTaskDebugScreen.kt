package io.legado.app.ui.autoTask

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.components.LegadoTopAppBar
import io.legado.app.ui.theme.LocalLegadoColors

@Composable
fun AutoTaskDebugScreen(
    state: AutoTaskDebugUiState,
    onRunAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val colors = LocalLegadoColors.current
    val scrollState = rememberScrollState()
    // Runs after the updated text has been measured, without View.post callbacks.
    LaunchedEffect(state.output, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            LegadoTopAppBar(stringResource(R.string.auto_task_debug), onBack)
            if (state.isLoading || state.isRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).testTag("task-debug-output")) {
                Text(
                    state.output, Modifier.padding(12.dp), color = colors.textPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
            }
            state.issue?.let { Text(stringResource(if (it == AutoTaskDebugIssue.Busy) R.string.auto_task_debug_busy else R.string.auto_task_debug_interrupted),
                Modifier.padding(horizontal = 12.dp).testTag("task-debug-issue"), color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, Modifier.padding(horizontal = 12.dp).testTag("task-debug-error"), color = MaterialTheme.colorScheme.error)
                TextButton(onRetry, Modifier.testTag("task-debug-retry"), enabled = !state.isLoading && !state.isRunning) { Text(stringResource(R.string.retry)) } }
            Button(
                onClick = onRunAgain,
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("task-debug-run"),
                enabled = !state.isLoading && !state.taskMissing && !state.closed,
            ) { Text(stringResource(R.string.auto_task_run_again)) }
        }
    }
}
