package io.legado.app.ui.config

import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R

@Composable fun BottomBarAssignmentRoute(model: BottomBarAssignmentViewModel, ready: () -> Boolean,
    close: () -> Unit, notice: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready); val currentClose by rememberUpdatedState(close); val currentNotice by rememberUpdatedState(notice)
    val invalid = stringResource(R.string.bottom_bar_skin_invalid); val noImages = stringResource(R.string.bottom_bar_skin_no_images)
    LaunchedEffect(state.pendingClose, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
        state.pendingClose?.let { issue ->
            model.delivered(issue)
            if (issue != BottomBarAssignmentIssue.Closed) currentNotice(if (issue == BottomBarAssignmentIssue.NoImages) noImages else invalid)
            currentClose()
        }
    }
    BottomBarAssignmentScreen(state, model.editName != null,
        BottomBarAssignmentActions(model::name, model::palette, model::choose, model::cancelPalette, model::save, model::close, model::load)) { image, modifier ->
        val bitmap by produceState<android.graphics.Bitmap?>(null, model, image) { value = model.preview(image) }
        bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit) }
    }
}
