package io.legado.app.ui.config

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.repository.BottomBarSkinCatalogIssue

@Composable
fun BottomBarSkinCatalogRoute(
    model: BottomBarSkinCatalogViewModel,
    ready: () -> Boolean,
    close: () -> Unit,
    deliver: (BottomBarSkinCatalogEffect) -> Unit,
    notice: (String) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val currentDeliver by rememberUpdatedState(deliver)
    val currentNotice by rememberUpdatedState(notice)
    val invalid = stringResource(R.string.bottom_bar_skin_invalid)
    val noImages = stringResource(R.string.bottom_bar_skin_no_images)
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) model.load() }
    LaunchedEffect(state.effect, state.issue, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
        state.issue?.let { issue ->
            model.clearIssue()
            currentNotice(if (issue == BottomBarSkinCatalogIssue.NoImages) noImages else invalid)
        }
        state.effect?.let { value ->
            model.delivered(value.id)?.let { effect ->
                try {
                    currentDeliver(effect)
                } catch (error: Exception) {
                    model.deliveryFailed(effect)
                } finally {
                    if (effect.type == BottomBarSkinCatalogEffectType.Changed)
                        model.changedDelivered(effect.id)
                }
            }
        }
    }
    BottomBarSkinCatalogScreen(
        state,
        BottomBarSkinCatalogActions(
            close,
            model::importPicker,
            model::activate,
            model::menu,
            model::cancelMenu,
            model::edit,
            model::export,
            model::share,
            model::requestDelete,
            model::confirmDelete,
            model::cancelDelete,
            model::scroll,
            model::load,
        ),
    ) { name ->
        val preview by
            produceState<List<Bitmap>>(emptyList(), model, name, state.previewRevision) {
                value = model.preview(name)
            }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            preview.forEach { Image(it.asImageBitmap(), null, Modifier.size(24.dp)) }
        }
    }
}
