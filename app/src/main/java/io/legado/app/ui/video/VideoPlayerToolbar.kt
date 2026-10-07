package io.legado.app.ui.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.legado.app.R

internal data class VideoPlayerToolbarState(
    val title: String = "",
    val customButtonVisible: Boolean = false,
    val favoriteActionVisible: Boolean = false,
    val isFavorite: Boolean = false,
    val loginActionVisible: Boolean = false,
    val menuExpanded: Boolean = false,
)

internal enum class VideoPlayerToolbarAction {
    Settings,
    Login,
    CopyVideoUrl,
    OpenOtherPlayer,
    EditSource,
    OpenLog,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoPlayerToolbar(
    state: VideoPlayerToolbarState,
    onBack: () -> Unit,
    onCustomButton: () -> Unit,
    onFavorite: () -> Unit,
    onFloatingWindow: () -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit,
    onMenuAction: (VideoPlayerToolbarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = {
            Text(
                text = state.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("video-toolbar-back")) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            if (state.customButtonVisible) {
                IconButton(
                    onClick = onCustomButton,
                    modifier = Modifier.testTag("video-toolbar-custom"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_custom),
                        contentDescription = stringResource(R.string.custom_button),
                    )
                }
            }
            if (state.favoriteActionVisible) {
                IconButton(
                    onClick = onFavorite,
                    modifier = Modifier.testTag("video-toolbar-favorite"),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (state.isFavorite) R.drawable.ic_star
                                else R.drawable.ic_star_border
                            ),
                        contentDescription =
                            stringResource(
                                if (state.isFavorite) R.string.in_favorites
                                else R.string.out_favorites
                            ),
                    )
                }
            }
            IconButton(
                onClick = onFloatingWindow,
                modifier = Modifier.testTag("video-toolbar-floating"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_float_window),
                    contentDescription = stringResource(R.string.float_window),
                )
            }
            Box {
                IconButton(
                    onClick = { onMenuExpandedChange(true) },
                    modifier = Modifier.testTag("video-toolbar-more"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more_menu),
                    )
                }
                DropdownMenu(
                    expanded = state.menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                ) {
                    ToolbarMenuItem(R.string.config_settings, "video-menu-settings") {
                        onMenuExpandedChange(false)
                        onMenuAction(VideoPlayerToolbarAction.Settings)
                    }
                    if (state.loginActionVisible) {
                        ToolbarMenuItem(R.string.login, "video-menu-login") {
                            onMenuExpandedChange(false)
                            onMenuAction(VideoPlayerToolbarAction.Login)
                        }
                    }
                    ToolbarMenuItem(R.string.copy_play_url, "video-menu-copy-url") {
                        onMenuExpandedChange(false)
                        onMenuAction(VideoPlayerToolbarAction.CopyVideoUrl)
                    }
                    ToolbarMenuItem(R.string.open_other_video_player, "video-menu-other-player") {
                        onMenuExpandedChange(false)
                        onMenuAction(VideoPlayerToolbarAction.OpenOtherPlayer)
                    }
                    ToolbarMenuItem(R.string.edit_book_source, "video-menu-edit-source") {
                        onMenuExpandedChange(false)
                        onMenuAction(VideoPlayerToolbarAction.EditSource)
                    }
                    ToolbarMenuItem(R.string.log, "video-menu-log") {
                        onMenuExpandedChange(false)
                        onMenuAction(VideoPlayerToolbarAction.OpenLog)
                    }
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
        windowInsets = WindowInsets.statusBars,
        colors = TopAppBarDefaults.topAppBarColors(),
    )
}

@Composable
private fun ToolbarMenuItem(
    titleRes: Int,
    testTag: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(titleRes)) },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}
