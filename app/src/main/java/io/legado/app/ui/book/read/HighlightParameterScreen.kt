package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R
import java.util.Locale
import kotlin.math.roundToInt

internal fun halfStepProgress(value: Float, min: Float, max: Float): Int =
    (((value.takeIf { it.isFinite() } ?: min).coerceIn(min, max) - min) * 2).roundToInt()

internal data class HighlightParameter(
    val tag: String,
    val label: Int,
    val progress: Int,
    val maximum: Int,
    val offset: Int = 0,
    val signed: Boolean = false,
)

@Composable
internal fun HighlightParameterScreen(
    title: Int,
    parameters: List<HighlightParameter>,
    onChange: (Int, Int) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                parameters.forEachIndexed { index, parameter ->
                    val label = stringResource(parameter.label)
                    val decrease = "$label ${stringResource(R.string.reduce)}"
                    val increase = "$label ${stringResource(R.string.plus)}"
                    Text(
                        "$label: " +
                            String.format(
                                Locale.getDefault(),
                                if (parameter.signed) "%+.1f" else "%.1f",
                                (parameter.progress - parameter.offset) / 2f,
                            ),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            { onChange(index, parameter.progress - 1) },
                            Modifier.testTag("${parameter.tag}-minus").semantics {
                                contentDescription = decrease
                            },
                            enabled = parameter.progress > 0,
                        ) {
                            Text("−")
                        }
                        Slider(
                            parameter.progress.toFloat(),
                            { onChange(index, it.roundToInt()) },
                            Modifier.weight(1f).testTag(parameter.tag).semantics {
                                contentDescription = label
                            },
                            valueRange = 0f..parameter.maximum.toFloat(),
                            steps = parameter.maximum - 1,
                        )
                        TextButton(
                            { onChange(index, parameter.progress + 1) },
                            Modifier.testTag("${parameter.tag}-plus").semantics {
                                contentDescription = increase
                            },
                            enabled = parameter.progress < parameter.maximum,
                        ) {
                            Text("+")
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onCancel, Modifier.testTag("highlight-parameter-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onConfirm, Modifier.testTag("highlight-parameter-confirm")) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}
