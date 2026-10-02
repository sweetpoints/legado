package io.legado.app.ui.main.bookshelf.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun BookshelfGroupCard(model: BookshelfGroupCardModel, layout: BookshelfCardLayout,
    onOpen: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier,
    gridTitle: BookshelfGridTitle = BookshelfGridTitle.Below, cover: @Composable (Modifier) -> Unit) {
    Surface(modifier.combinedClickable(onClick = onOpen, onLongClick = onEdit).testTag("shelf-group-${model.key}"),
        color = MaterialTheme.colorScheme.surface) {
        if (layout == BookshelfCardLayout.Grid) {
            Column(Modifier.padding(4.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(.75f)) {
                    cover(Modifier.fillMaxSize())
                    if (gridTitle == BookshelfGridTitle.Overlay) Text(model.name,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f))))
                            .padding(horizontal = 4.dp, vertical = 8.dp).testTag("shelf-group-name-${model.key}"),
                        color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (gridTitle == BookshelfGridTitle.Below) Text(model.name,
                    Modifier.fillMaxWidth().padding(top = 4.dp).testTag("shelf-group-name-${model.key}"),
                    fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            cover(Modifier.size(72.dp, 96.dp))
            Text(model.name, Modifier.weight(1f).testTag("shelf-group-name-${model.key}"), fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
