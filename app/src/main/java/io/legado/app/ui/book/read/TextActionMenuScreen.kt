package io.legado.app.ui.book.read

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.repository.TextAction

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TextActionMenuScreen(state: TextActionMenuState, onAction: (String) -> Unit,
    onLongAction: () -> Unit, onMore: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val config = LocalConfiguration.current
    val maxWidth = (config.screenWidthDp.dp * .95f).coerceAtLeast(100.dp)
    Surface(modifier.widthIn(max = maxWidth), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp) {
        Column(Modifier.padding(5.dp).testTag("text-action-popup")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!state.more) FlowRow(Modifier.widthIn(max = (maxWidth - if (state.snapshot.more.isEmpty()) 10.dp else 58.dp).coerceAtLeast(48.dp))) {
                    state.snapshot.primary.forEach { action -> TextActionLabel(action, onAction, onLongAction) }
                }
                if (state.loading && state.snapshot.primary.isEmpty()) CircularProgressIndicator(Modifier.size(32.dp).padding(4.dp))
                if (state.snapshot.more.isNotEmpty()) Box(Modifier.size(48.dp).testTag("text-action-more")
                    .combinedClickable(onClick = onMore, onLongClick = onEdit, role = Role.Button), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (state.more) R.drawable.ic_arrow_back else R.drawable.ic_more_vert),
                        stringResource(if (state.more) R.string.back else R.string.more_menu))
                }
            }
            if (state.more) Column(Modifier.heightIn(max = config.screenHeightDp.dp * .65f).verticalScroll(rememberScrollState()).testTag("text-action-more-list")) {
                state.snapshot.more.forEach { action -> TextActionLabel(action, onAction, onLongAction, Modifier.fillMaxWidth()) }
            }
        }
    }
}
@Composable private fun TextActionLabel(action: TextAction, onAction: (String) -> Unit, onLongAction: () -> Unit,
    modifier: Modifier = Modifier) {
    Text(action.title, modifier.heightIn(min = 48.dp).testTag("text-action-${action.id}")
        .combinedClickable(onClick = { onAction(action.id) }, onLongClick = onLongAction, role = Role.Button).padding(horizontal = 8.dp, vertical = 13.dp),
        fontSize = 14.sp, maxLines = 1)
}
