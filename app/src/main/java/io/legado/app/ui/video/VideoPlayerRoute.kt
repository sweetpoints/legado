package io.legado.app.ui.video

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.data.repository.CoverRequest
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.ui.dict.DictionaryResultAction

@Composable
internal fun VideoPlayerRoute(
    toolbarState: VideoPlayerToolbarState,
    chapterState: VideoChapterRailState,
    coverRequest: CoverRequest,
    coverDescription: String,
    headerState: VideoBookHeaderState,
    introState: VideoBookIntroState,
    playerHeight: Dp,
    backgroundColor: Int,
    showBookContent: Boolean,
    fullscreen: Boolean,
    onPlayerCreated: (VideoPlayer) -> Unit,
    onBack: () -> Unit,
    onCustomButton: () -> Unit,
    onFavorite: () -> Unit,
    onFloatingWindow: () -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit,
    onMenuAction: (VideoPlayerToolbarAction) -> Unit,
    onVolumeSelected: (Int) -> Unit,
    onEpisodeSelected: (Int) -> Unit,
    onOpenCatalog: () -> Unit,
    onIntroAction: (DictionaryResultAction) -> Unit,
    onIntroLink: (String) -> Unit,
    onIntroImage: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color(backgroundColor))) {
        if (!fullscreen) {
            VideoPlayerToolbar(
                state = toolbarState,
                onBack = onBack,
                onCustomButton = onCustomButton,
                onFavorite = onFavorite,
                onFloatingWindow = onFloatingWindow,
                onMenuExpandedChange = onMenuExpandedChange,
                onMenuAction = onMenuAction,
            )
        }
        // Activity owns player transfer and release; Compose only hosts the native GSY engine view.
        AndroidView(
            factory = { context: Context -> VideoPlayer(context).also(onPlayerCreated) },
            modifier = Modifier.fillMaxWidth().height(playerHeight),
        )
        if (!fullscreen && showBookContent) {
            VideoBookInfoScreen(
                coverRequest = coverRequest,
                coverDescription = coverDescription,
                headerState = headerState,
                introState = introState,
                onIntroAction = onIntroAction,
                onIntroLink = onIntroLink,
                onIntroImage = onIntroImage,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
            )
            VideoChapterScreen(
                state = chapterState,
                onVolumeSelected = onVolumeSelected,
                onEpisodeSelected = onEpisodeSelected,
                onOpenCatalog = onOpenCatalog,
                modifier =
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 20.dp),
            )
        }
    }
}
