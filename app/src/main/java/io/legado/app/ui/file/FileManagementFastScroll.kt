package io.legado.app.ui.file

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlinx.coroutines.*

/** Preserve direct thumb navigation from the former FastScrollRecyclerView. */
@Composable internal fun FileManagementFastScroll(list:LazyListState,modifier:Modifier=Modifier) {
    val count=list.layoutInfo.totalItemsCount
    if(count<=list.layoutInfo.visibleItemsInfo.size) return
    val scope=rememberCoroutineScope();val color=MaterialTheme.colorScheme.onSurfaceVariant
    val description=stringResource(R.string.file_management_scroll)
    Canvas(modifier.width(24.dp).fillMaxHeight().testTag("file-management-fast-scroll").semantics {
        contentDescription=description
        progressBarRangeInfo=ProgressBarRangeInfo(list.firstVisibleItemIndex.toFloat(),0f..(count-1).toFloat())
        setProgress { value->scope.launch { list.scrollToItem(value.toInt().coerceIn(0,count-1)) };true }
    }.pointerInput(list,count) {
        coroutineScope {
            var work:Job?=null
            fun scroll(y:Float) { val index=(y/size.height.coerceAtLeast(1)*(count-1)).toInt().coerceIn(0,count-1);work?.cancel();work=launch { list.scrollToItem(index) } }
            detectVerticalDragGestures(onDragStart={scroll(it.y)},onVerticalDrag={ change,_->scroll(change.position.y);change.consume() })
        }
    }) {
        val visible=list.layoutInfo.visibleItemsInfo.size
        val height=(size.height*visible/count.toFloat()).coerceAtLeast(32.dp.toPx()).coerceAtMost(size.height)
        val fraction=list.firstVisibleItemIndex.toFloat()/(count-visible).coerceAtLeast(1)
        drawRoundRect(color.copy(alpha=.55f),topLeft=Offset(size.width-6.dp.toPx(),(size.height-height)*fraction.coerceIn(0f,1f)),size=Size(4.dp.toPx(),height),cornerRadius=CornerRadius(2.dp.toPx()))
    }
}
