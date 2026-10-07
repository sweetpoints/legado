package io.legado.app.ui.book.source.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable
internal fun BookSourceMigrationDialog(
    state: BookSourceComposeState,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onRetry: () -> Unit,
) {
    if (!state.migrationRunning && state.migrationReport == null && state.migrationError == null)
        return
    val report = state.migrationReport
    val preview = report?.preview
    val stale = report != null && report.revision != state.document?.revision
    val canApply =
        state.migrationAvailable &&
            !state.busy &&
            !state.migrationRunning &&
            !stale &&
            state.document?.finished == false &&
            state.document?.nativeRequest == null &&
            preview != null &&
            !preview.requiresManualWork &&
            preview.issues.isEmpty() &&
            preview.status == "unverified" &&
            !preview.candidateJson.isNullOrBlank()
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("迁移到 Dart / V8") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("迁移预览尚未运行书源，也未验证请求、内容或登录状态。应用后请按正常流程保存，再调试书源。")
                if (state.migrationRunning) {
                    Text("正在生成当前草稿的迁移候选…")
                    LinearProgressIndicator()
                }
                state.migrationError?.let { Text(it) }
                if (stale) Text("草稿已更改，此预览已过期。请重新生成后再应用。")
                if (preview != null) {
                    Text(
                        if (preview.requiresManualWork || preview.issues.isNotEmpty())
                            "存在需要手工迁移的项目，不能直接应用或启用此候选。"
                        else "候选未验证，可应用到当前草稿，由 Dart / V8 执行。"
                    )
                    SelectionContainer {
                        Column {
                            preview.issues.forEach { issue ->
                                Text("${issue.path}\n${issue.code}\n${issue.message}")
                            }
                        }
                    }
                    preview.candidateJson?.let { candidate ->
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(candidate)) },
                            modifier = Modifier.testTag("sourceMigrationCopy"),
                        ) {
                            Text("复制候选 JSON")
                        }
                    }
                }
                if (!state.migrationRunning && (stale || state.migrationError != null)) {
                    TextButton(onClick = onRetry) { Text("重新生成预览") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onApply,
                enabled = canApply,
                modifier = Modifier.testTag("sourceMigrationApply"),
            ) {
                Text("应用到草稿")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
