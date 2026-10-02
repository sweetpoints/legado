package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.ClickActionRegion

internal val clickActionTitles = linkedMapOf(
    -1 to R.string.non_action, 0 to R.string.menu, 1 to R.string.next_page, 2 to R.string.prev_page,
    3 to R.string.next_chapter, 4 to R.string.previous_chapter, 5 to R.string.read_aloud_prev_paragraph,
    6 to R.string.read_aloud_next_paragraph, 7 to R.string.bookmark_add, 8 to R.string.edit_content,
    9 to R.string.replace_state_change, 10 to R.string.chapter_list, 11 to R.string.search_content,
    12 to R.string.sync_book_progress_t, 13 to R.string.read_aloud_pause_resume,
)
@Composable internal fun ClickActionSettingsScreen(state: ClickActionSettingsUiState,
    onRegion: (ClickActionRegion) -> Unit, onAction: (Int) -> Unit, onDismissPicker: () -> Unit,
    onClose: () -> Unit, modifier: Modifier = Modifier) {
    val translucent = colorResource(R.color.translucent)
    Surface(modifier.fillMaxSize().testTag("click-action-root"), color = translucent, contentColor = Color.White) {
        Column(Modifier.navigationBarsPadding().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = translucent, contentColor = Color.White, shape = RoundedCornerShape(3.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.click_regional_config), Modifier.weight(1f))
                    IconButton(onClose, Modifier.testTag("click-action-close")) {
                        Icon(painterResource(R.drawable.ic_baseline_close), stringResource(R.string.close))
                    }
                }
            }
            ClickActionRegion.entries.chunked(3).forEach { regions ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    regions.forEach { region ->
                        Surface(onClick = { onRegion(region) }, color = translucent, contentColor = Color.White,
                            shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight()
                                .testTag("click-action-region-${region.name}")) {
                            Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                                val title = clickActionTitles[state.actions[region] ?: region.defaultAction]
                                Text(title?.let { stringResource(it) }.orEmpty())
                            }
                        }
                    }
                }
            }
        }
    }
    state.editing?.let { region ->
        AlertDialog(onDismissRequest = onDismissPicker, title = { Text(stringResource(R.string.select_action)) },
            text = { LazyColumn(Modifier.testTag("click-action-picker")) {
                items(clickActionTitles.keys.toList(), key = { it }) { action ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("click-action-option-$action")
                        .selectable(state.actions[region] == action, role = Role.RadioButton, onClick = { onAction(action) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.actions[region] == action, onClick = null)
                        Text(stringResource(clickActionTitles.getValue(action)))
                    }
                }
            } }, confirmButton = {}, dismissButton = {
                TextButton(onDismissPicker, Modifier.testTag("click-action-picker-cancel")) { Text(stringResource(android.R.string.cancel)) }
            })
    }
}
