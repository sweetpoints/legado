package io.legado.app.ui.video

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.CoverRequest
import io.legado.app.ui.components.cover.ComposeCover
import io.legado.app.ui.dict.DictionaryResultAction

@Composable
internal fun VideoBookInfoScreen(
    coverRequest: CoverRequest,
    coverDescription: String,
    headerState: VideoBookHeaderState,
    introState: VideoBookIntroState,
    onIntroAction: (DictionaryResultAction) -> Unit,
    onIntroLink: (String) -> Unit,
    onIntroImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize().padding(bottom = 12.dp)) {
        ComposeCover(
            request = coverRequest,
            modifier = Modifier.size(110.dp, 160.dp),
            contentDescription = coverDescription,
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            VideoBookHeaderScreen(headerState)
            VideoBookIntroScreen(
                state = introState,
                onAction = onIntroAction,
                onLink = onIntroLink,
                onImage = onIntroImage,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
