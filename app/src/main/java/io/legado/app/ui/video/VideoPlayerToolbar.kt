package io.legado.app.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("video-toolbar-back")) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            text = state.title,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (state.customButtonVisible) {
            IconButton(
                onClick = onCustomButton,
                modifier = Modifier.testTag("video-toolbar-custom"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_custom),
                    contentDescription = stringResource(R.string.custom_button),
                    tint = MaterialTheme.colorScheme.onPrimary,
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
                            if (state.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border
                        ),
                    contentDescription =
                        stringResource(
                            if (state.isFavorite) R.string.in_favorites else R.string.out_favorites
                        ),
                    tint = MaterialTheme.colorScheme.onPrimary,
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
                tint = MaterialTheme.colorScheme.onPrimary,
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
                    tint = MaterialTheme.colorScheme.onPrimary,
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
    }
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
