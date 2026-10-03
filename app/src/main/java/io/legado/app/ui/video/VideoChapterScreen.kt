package io.legado.app.ui.video

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

internal data class VideoChapterRailState(
    val volumes: List<String> = emptyList(),
    val episodes: List<String> = emptyList(),
    val selectedVolume: Int = -1,
    val selectedEpisode: Int = -1,
)

@Composable
internal fun VideoChapterScreen(
    state: VideoChapterRailState,
    onVolumeSelected: (Int) -> Unit,
    onEpisodeSelected: (Int) -> Unit,
    onOpenCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val volumeListState = rememberLazyListState()
    val episodeListState = rememberLazyListState()
    ScrollSelectedItem(state.volumes, state.selectedVolume, volumeListState)
    ScrollSelectedItem(state.episodes, state.selectedEpisode, episodeListState)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.volumes.isNotEmpty()) {
            ChapterRail(
                items = state.volumes,
                selectedIndex = state.selectedVolume,
                listState = volumeListState,
                itemWidth = 80.dp,
                itemHeight = 40.dp,
                fontSize = 11.sp,
                testTagPrefix = "video-volume",
                onItemSelected = onVolumeSelected,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.episodes.isNotEmpty()) {
                ChapterRail(
                    items = state.episodes,
                    selectedIndex = state.selectedEpisode,
                    listState = episodeListState,
                    itemWidth = 100.dp,
                    itemHeight = 50.dp,
                    fontSize = 12.sp,
                    testTagPrefix = "video-episode",
                    modifier = Modifier.weight(1f),
                    onItemSelected = onEpisodeSelected,
                )
            }
            IconButton(
                onClick = onOpenCatalog,
                modifier = Modifier.testTag("video-open-catalog"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chapter_list),
                    contentDescription = stringResource(R.string.chapter_list),
                )
            }
        }
    }
}

@Composable
private fun ChapterRail(
    items: List<String>,
    selectedIndex: Int,
    listState: LazyListState,
    itemWidth: Dp,
    itemHeight: Dp,
    fontSize: TextUnit,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
    onItemSelected: (Int) -> Unit,
) {
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items) { index, title ->
            val selected = index == selectedIndex
            Surface(
                modifier =
                    Modifier.width(itemWidth)
                        .height(itemHeight)
                        .testTag("$testTagPrefix-$index")
                        .selectable(selected, role = Role.Tab) { onItemSelected(index) },
                shape = RoundedCornerShape(4.dp),
                color = colorResource(R.color.card_bg_water),
                border = BorderStroke(1.dp, colorResource(R.color.card_border_water)),
            ) {
                Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = title,
                        color =
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollSelectedItem(items: List<String>, selectedIndex: Int, state: LazyListState) {
    LaunchedEffect(items, selectedIndex) {
        if (selectedIndex in items.indices) {
            state.animateScrollToItem(selectedIndex)
        }
    }
}
