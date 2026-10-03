package io.legado.app.ui.book.audio

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.utils.toDurationTime

internal data class AudioLyric(val time: Int, val text: String)

internal data class AudioPlayUiState(
    val title: String = "",
    val subtitle: String = "",
    val cover: String? = null,
    val coverOrigin: String? = null,
    val coverImage: Bitmap? = null,
    val backdropImage: Bitmap? = null,
    val bookNavigation: String? = null,
    val folderRequest: String? = null,
    val cacheReady: String? = null,
    val selectedCacheFolder: String? = null,
    val askShelf: Boolean = false,
    val shelfAdded: Boolean = false,
    val closeRequested: Boolean = false,
    val ready: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val progress: Int = 0,
    val duration: Int = 0,
    val buffer: Int = 0,
    val supportsSpeed: Boolean = true,
    val speed: Float = 1f,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val playMode: Int = R.drawable.ic_play_24dp,
    val timerMinutes: Int = 0,
    val timerChapters: Int = 0,
    val customButton: Boolean = false,
    val hasLogin: Boolean = false,
    val wakeLock: Boolean = false,
    val lyrics: List<AudioLyric> = emptyList(),
)

internal sealed interface AudioCacheAction {
    data class Download(val bookUrl: String, val start: Int, val endInclusive: Int) :
        AudioCacheAction

    data class Clear(val bookUrl: String, val chapter: io.legado.app.data.entities.BookChapter) :
        AudioCacheAction
}

internal enum class AudioPlayAction {
    Play,
    Stop,
    Next,
    Previous,
    Chapters,
    Mode,
    Timer,
}

internal fun parseAudioLyrics(text: String?): List<AudioLyric> {
    val pattern = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
    return text
        .orEmpty()
        .lineSequence()
        .flatMap { line ->
            val body = line.replace(pattern, "").trim()
            pattern.findAll(line).mapNotNull { match ->
                val minute = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                if (minute > Int.MAX_VALUE / 60000 || second > 59) return@mapNotNull null
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                val time = minute * 60000L + second * 1000L + fraction
                if (time > Int.MAX_VALUE || body.isEmpty()) null else AudioLyric(time.toInt(), body)
            }
        }
        .sortedBy { it.time }
        .toList()
}

@Composable
internal fun AudioPlayRoute(
    model: AudioPlayViewModel,
    close: () -> Unit,
    folder: () -> Unit,
    cache: (String, String?) -> Unit,
    navigate: (String) -> Unit,
    back: () -> Unit,
    menu: (Int) -> Unit,
    shelfResult: () -> Unit,
    addShelf: () -> Unit,
    discardShelf: () -> Unit,
    action: (AudioPlayAction) -> Unit,
    seek: (Int) -> Unit,
    speed: (Float) -> Unit,
    lyricSeek: (Int) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latestClose by rememberUpdatedState(close)
    val latestFolder by rememberUpdatedState(folder)
    val latestCache by rememberUpdatedState(cache)
    val latestShelfResult by rememberUpdatedState(shelfResult)
    val latestNavigate by rememberUpdatedState(navigate)
    LaunchedEffect(owner, model) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            model.state.collect { current ->
                current.bookNavigation?.let { key ->
                    model.update { copy(bookNavigation = null) }
                    latestNavigate(key)
                }
                current.folderRequest?.let {
                    model.update { copy(folderRequest = null) }
                    latestFolder()
                }
                current.cacheReady?.let { session ->
                    model.update { copy(cacheReady = null, selectedCacheFolder = null) }
                    latestCache(session, current.selectedCacheFolder)
                }
                if (current.shelfAdded) {
                    model.update { copy(shelfAdded = false) }
                    latestShelfResult()
                }
                if (current.closeRequested) {
                    // Claim before delivering native finish so rotation cannot replay it.
                    model.update { copy(closeRequested = false) }
                    latestClose()
                }
            }
        }
    }
    AudioPlayScreen(state, back, menu, action, seek, speed, lyricSeek)
    if (state.askShelf)
        AlertDialog(
            onDismissRequest = { model.update { copy(askShelf = false) } },
            title = { Text(stringResource(R.string.add_to_bookshelf)) },
            text = { Text(stringResource(R.string.check_add_bookshelf, state.title)) },
            confirmButton = { TextButton(addShelf) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(discardShelf) { Text(stringResource(R.string.no)) } },
        )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AudioPlayScreen(
    state: AudioPlayUiState,
    back: () -> Unit,
    menu: (Int) -> Unit,
    action: (AudioPlayAction) -> Unit,
    seek: (Int) -> Unit,
    speed: (Float) -> Unit,
    lyricSeek: (Int) -> Unit,
) {
    var speedOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            AudioPlayTopBar(state, back, menu)
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            state.backdropImage?.let { bitmap ->
                Image(
                    bitmap.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.15f,
                )
            }
            val landscape = maxWidth > maxHeight
            if (landscape)
                Row(Modifier.fillMaxSize()) {
                    AudioPlayCover(state, Modifier.weight(1f).fillMaxHeight().padding(24.dp))
                    AudioPlaybackControls(
                        state,
                        action,
                        seek,
                        lyricSeek,
                        { speedOpen = true },
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            else
                Column(Modifier.fillMaxSize()) {
                    AudioPlayCover(state, Modifier.fillMaxWidth().weight(1f).padding(24.dp))
                    AudioPlaybackControls(
                        state,
                        action,
                        seek,
                        lyricSeek,
                        { speedOpen = true },
                        Modifier.fillMaxWidth().weight(1f),
                    )
                }
        }
    }
    if (speedOpen)
        AlertDialog(
            onDismissRequest = { speedOpen = false },
            title = { Text(stringResource(R.string.speed_control)) },
            text = {
                AudioSliderScreen(AudioSliderState(AudioSliderMode.Speed, state.speed), speed)
            },
            confirmButton = {
                TextButton({ speedOpen = false }) { Text(stringResource(R.string.ok)) }
            },
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioPlayTopBar(state: AudioPlayUiState, back: () -> Unit, menu: (Int) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(state.title, maxLines = 1) },
        navigationIcon = {
            IconButton(back) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    stringResource(R.string.back),
                )
            }
        },
        actions = {
            Box {
                IconButton({ menuOpen = true }) {
                    Icon(
                        painterResource(R.drawable.ic_more_vert),
                        stringResource(R.string.menu),
                    )
                }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    val entries =
                        listOf(
                            R.id.menu_custom_btn to R.string.custom_button,
                            R.id.menu_change_source to R.string.change_origin,
                            R.id.menu_login to R.string.login,
                            R.id.menu_copy_audio_url to R.string.copy_play_url,
                            R.id.menu_audio_cache_folder to R.string.audio_cache_select_folder,
                            R.id.menu_audio_cache_range to R.string.audio_cache_range,
                            R.id.menu_clear_current_audio_cache to
                                R.string.audio_cache_clear_current_chapter,
                            R.id.menu_edit_source to R.string.edit_book_source,
                            R.id.menu_wake_lock to R.string.audio_play_wake_lock,
                            R.id.menu_skip_credits to R.string.skip_book_credits,
                            R.id.menu_log to R.string.log,
                        )
                    entries
                        .filter { (id, _) ->
                            (id != R.id.menu_custom_btn || state.customButton) &&
                                (id != R.id.menu_login || state.hasLogin)
                        }
                        .forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    menuOpen = false
                                    menu(id)
                                },
                                trailingIcon =
                                    if (id == R.id.menu_wake_lock) {
                                        { Checkbox(state.wakeLock, null) }
                                    } else null,
                            )
                        }
                }
            }
        },
    )
}

@Composable
private fun AudioPlayCover(state: AudioPlayUiState, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        state.coverImage?.let { bitmap ->
            Image(
                bitmap.asImageBitmap(),
                state.title,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        if (state.loading) CircularProgressIndicator()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioPlaybackControls(
    state: AudioPlayUiState,
    action: (AudioPlayAction) -> Unit,
    seek: (Int) -> Unit,
    lyricSeek: (Int) -> Unit,
    onSpeed: () -> Unit,
    modifier: Modifier,
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.title, state.chapterIndex) { scrub = null }
    val progress = scrub ?: state.progress.toFloat()
    Column(
        modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(state.subtitle, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        if (state.chapterCount > 0)
            Text(
                stringResource(
                    R.string.audio_chapter_progress,
                    state.chapterIndex + 1,
                    state.chapterCount,
                )
            )
        if (state.lyrics.isNotEmpty()) {
            val list = rememberLazyListState()
            val active = state.lyrics.indexOfLast { it.time <= state.progress }
            LaunchedEffect(active) { if (active >= 0) list.animateScrollToItem(active) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(state.lyrics) { index, lyric ->
                    Text(
                        lyric.text,
                        Modifier.fillMaxWidth().clickable { lyricSeek(lyric.time) }.padding(12.dp),
                        color =
                            if (index == active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else Spacer(Modifier.weight(1f))
        LinearProgressIndicator(
            progress = {
                (state.buffer.toFloat() / state.duration.coerceAtLeast(1)).coerceIn(
                    0f,
                    1f,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Slider(
            progress.coerceIn(0f, state.duration.coerceAtLeast(1).toFloat()),
            { scrub = it },
            enabled = state.ready && state.duration > 0,
            valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
            onValueChangeFinished = {
                scrub?.let { seek(it.toInt()) }
                scrub = null
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(progress.toInt().toDurationTime())
            Text(state.duration.toDurationTime())
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                { action(AudioPlayAction.Previous) },
                enabled = state.ready && state.chapterIndex > 0,
            ) {
                Icon(
                    painterResource(R.drawable.ic_skip_previous),
                    stringResource(R.string.previous),
                )
            }
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier =
                    Modifier.size(64.dp)
                        .combinedClickable(
                            enabled = state.ready,
                            onClick = { action(AudioPlayAction.Play) },
                            onLongClick = { action(AudioPlayAction.Stop) },
                        ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(
                            if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
                        ),
                        stringResource(if (state.playing) R.string.pause else R.string.audio_play),
                    )
                }
            }
            IconButton(
                { action(AudioPlayAction.Next) },
                enabled = state.ready && state.chapterIndex < state.chapterCount - 1,
            ) {
                Icon(
                    painterResource(R.drawable.ic_skip_next),
                    stringResource(R.string.next),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ action(AudioPlayAction.Mode) }, enabled = state.ready) {
                Icon(
                    painterResource(state.playMode),
                    stringResource(R.string.play_mode),
                )
            }
            TextButton({ action(AudioPlayAction.Chapters) }, enabled = state.ready) {
                Text(stringResource(R.string.chapter_list))
            }
            if (state.supportsSpeed)
                TextButton(onSpeed) {
                    Text("%.1fX".format(java.util.Locale.ROOT, state.speed))
                }
            TextButton({ action(AudioPlayAction.Timer) }) {
                Text(
                    when {
                        state.timerChapters > 0 ->
                            stringResource(
                                R.string.audio_stop_chapters,
                                state.timerChapters,
                            )
                        state.timerMinutes > 0 ->
                            stringResource(R.string.timer_m, state.timerMinutes)
                        else -> stringResource(R.string.set_timer)
                    }
                )
            }
        }
    }
}
