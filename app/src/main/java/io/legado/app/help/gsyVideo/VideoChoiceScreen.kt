package io.legado.app.help.gsyVideo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Detached titles only: no chapter entities or player resources enter composition. */
@Composable
fun VideoChoiceScreen(
    title: String,
    choices: List<String>,
    initialSelection: Int = -1,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastChoiceIndex = (choices.size - 1).coerceAtLeast(0)
    val visibleSelection = initialSelection.coerceIn(0, lastChoiceIndex)
    val listState = rememberLazyListState(visibleSelection)
    Surface(modifier.fillMaxSize()) {
        Column {
            Text(title, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(choices) { index, label ->
                    Text(
                        label,
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("video-choice-$index")
                            .semantics { selected = index == initialSelection }
                            .clickable(role = Role.Button) { onSelect(index) }
                            .padding(16.dp),
                        color =
                            if (index == initialSelection) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            }
        }
    }
}
