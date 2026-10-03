package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.constant.AppLog
import io.legado.app.data.image.GlideMangaImageRepository
import io.legado.app.data.repository.MangaNativeKind
import io.legado.app.data.repository.MangaNativePhase
import io.legado.app.data.repository.MangaNativeRequest
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class MangaReaderHostActions(
    val ready: () -> Boolean,
    val dispatch: (MangaNativeRequest) -> Unit,
    val applyWindow: (MangaReaderUiState) -> Unit,
    val finish: (Boolean) -> Unit,
    val shelfAdded: () -> Unit,
    val notify: (String) -> Unit,
)

@Composable
internal fun MangaReaderRoute(
    viewModel: MangaReaderComposeViewModel,
    actions: MangaReaderHostActions,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val host by rememberUpdatedState(actions)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val imageRepository = remember(context) { GlideMangaImageRepository(context) }
    var time by remember {
        mutableStateOf(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
    }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                viewModel.state.collect { value ->
                    if (!host.ready()) return@collect
                    host.applyWindow(value)
                    if (value.shelfAdded) {
                        host.shelfAdded()
                        viewModel.consumeShelfAdded()
                    }
                    if (value.finishRequested) {
                        host.finish(value.deletedResult)
                        return@collect
                    }
                    value.nativeRequests
                        .firstOrNull { it.phase == MangaNativePhase.Pending }
                        ?.let { request ->
                            try {
                                viewModel.claimNative(
                                    ticket = request.ticket,
                                    resumed = {
                                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                            host.ready() &&
                                            viewModel.state.value.sessionId == value.sessionId
                                    },
                                    dispatch = { host.dispatch(it) },
                                )
                            } catch (error: Exception) {
                                currentCoroutineContext().ensureActive()
                                AppLog.put("打开漫画菜单页面失败", error)
                                host.notify(error.localizedMessage ?: "打开页面失败")
                            }
                        }
                }
            }
            launch { viewModel.notifications.collect { host.notify(it) } }
            launch {
                while (true) {
                    time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                    delay(60_000 - System.currentTimeMillis() % 60_000)
                }
            }
        }
    }
    MangaReaderScreen(
        state = state,
        imageRepository = imageRepository,
        readerActive = active.isAtLeast(Lifecycle.State.RESUMED),
        timeText = time,
        onCurrentItem = viewModel::currentItem,
        onCommandHandled = viewModel::commandHandled,
        onMenu = viewModel::setMenu,
        onPage = viewModel::page,
        onLongPress = { viewModel.saveImage(it.imageUrl) },
        onRetry = viewModel::retry,
        onExit = viewModel::requestExit,
        onMenuAction = { action -> mangaMenuAction(viewModel, action) },
        onSetting = viewModel::setSetting,
        onChapter = viewModel::chapter,
        onSkipPage = viewModel::skipToPage,
        onPreload = viewModel::setPreload,
        onAutoSpeed = viewModel::setAutoSpeed,
        onCloudProgress = viewModel::resolveCloudProgress,
        onResolveExit = viewModel::resolveExit,
        onDismissExit = viewModel::dismissExit,
        modifier = modifier,
    )
}

private fun mangaMenuAction(viewModel: MangaReaderComposeViewModel, action: MangaMenuAction) {
    when (action) {
        MangaMenuAction.BookInfo -> viewModel.enqueueNative(MangaNativeKind.BookInfo)
        MangaMenuAction.Catalog -> viewModel.enqueueNative(MangaNativeKind.Catalog)
        MangaMenuAction.ChangeSource -> {
            viewModel.setMenu(false)
            viewModel.enqueueNative(MangaNativeKind.ChangeSource)
        }
        MangaMenuAction.Refresh -> viewModel.refreshChapter()
        MangaMenuAction.Download -> viewModel.enqueueNative(MangaNativeKind.Download)
        MangaMenuAction.ColorFilter -> {
            viewModel.setMenu(false)
            viewModel.enqueueNative(MangaNativeKind.ColorFilter)
        }
        MangaMenuAction.Footer -> viewModel.enqueueNative(MangaNativeKind.FooterSettings)
        MangaMenuAction.EpaperSettings -> viewModel.enqueueNative(MangaNativeKind.EpaperSettings)
        MangaMenuAction.Browser ->
            viewModel.enqueueNative(
                if (viewModel.state.value.settings.useExternalBrowser)
                    MangaNativeKind.ExternalBrowser
                else MangaNativeKind.ChapterBrowser
            )
        MangaMenuAction.AutoPage -> viewModel.setAutomaticPaging(page = true)
        MangaMenuAction.AutoScroll -> viewModel.setAutomaticPaging(page = false)
        MangaMenuAction.Preload,
        MangaMenuAction.AutoSpeed -> Unit
    }
}
