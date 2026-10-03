package io.legado.app.ui.widget.number

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun NumberPickerScreen(
    config: NumberPickerConfig,
    value: Int,
    input: String,
    finished: Boolean,
    customLabel: String?,
    onValue: (Int) -> Unit,
    onInput: (String) -> Unit,
    onConfirm: () -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val finishInput = {
        focus.clearFocus()
        keyboard?.hide()
    }
    val listState = rememberLazyListState(config.wheelIndex(value))
    LaunchedEffect(config, value) {
        if (!listState.isScrollInProgress)
            listState.scrollToItem(config.wheelIndex(value, listState.firstVisibleItemIndex))
    }
    LaunchedEffect(config, listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val middle = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            val index =
                layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index
            index to listState.isScrollInProgress
        }
            .distinctUntilChanged()
            .collect { (index, scrolling) ->
                if (scrolling && !finished && index != null) onValue(config.valueAt(index))
            }
    }
    Surface(
        modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (config.title.isNotEmpty())
                Text(config.title, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    { onValue(config.step(value, -1)) },
                    enabled = !finished && (config.wraps || value > config.minimum),
                    modifier = Modifier.testTag("number-minus"),
                ) {
                    Icon(painterResource(R.drawable.ic_reduce), stringResource(R.string.reduce))
                }
                LazyColumn(
                    state = listState,
                    flingBehavior = rememberSnapFlingBehavior(listState),
                    userScrollEnabled = !finished,
                    contentPadding = PaddingValues(vertical = 48.dp),
                    modifier =
                        Modifier.weight(1f).height(144.dp).testTag("number-wheel").semantics {
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(
                                    value.toFloat(),
                                    config.minimum.toFloat()..config.maximum.toFloat(),
                                    (config.count - 2).coerceAtLeast(0),
                                )
                            setProgress {
                                onValue(it.roundToInt().coerceIn(config.minimum, config.maximum))
                                true
                            }
                        },
                ) {
                    items(config.wheelCount, key = { it }) { index ->
                        val item = config.valueAt(index)
                        Row(
                            Modifier.fillMaxWidth()
                                .height(48.dp)
                                .testTag("number-item-$index")
                                .clickable(enabled = !finished) { onValue(item) },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                config.label(item),
                                color =
                                    if (item == value) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                style =
                                    if (item == value) MaterialTheme.typography.titleLarge
                                    else MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                IconButton(
                    { onValue(config.step(value, 1)) },
                    enabled = !finished && (config.wraps || value < config.maximum),
                    modifier = Modifier.testTag("number-plus"),
                ) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.plus))
                }
            }
            OutlinedTextField(
                input,
                onInput,
                enabled = !finished,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("number-input"),
                label = { Text(stringResource(R.string.number_picker_value)) },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            when {
                                config.labels != null -> KeyboardType.Text
                                config.decimal -> KeyboardType.Decimal
                                else -> KeyboardType.Number
                            },
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            finishInput()
                            onConfirm()
                        }
                    ),
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                customLabel?.let {
                    TextButton(
                        {
                            finishInput()
                            onCustom()
                        },
                        enabled = !finished,
                        modifier = Modifier.testTag("number-custom"),
                    ) {
                        Text(it)
                    }
                }
                TextButton(
                    {
                        finishInput()
                        onDismiss()
                    },
                    enabled = !finished,
                    modifier = Modifier.testTag("number-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    {
                        finishInput()
                        onConfirm()
                    },
                    enabled = !finished,
                    modifier = Modifier.testTag("number-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}
