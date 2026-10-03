package io.legado.app.ui.book.manga

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.image.MangaImageRepository
import io.legado.app.data.image.MangaImageRequest
import io.legado.app.data.preferences.MangaColorFilterValues
import io.legado.app.ui.book.manga.entities.EpaperTransformation
import io.legado.app.ui.book.manga.entities.GrayscaleTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class MangaViewportOptions(
    val horizontal: Boolean = false,
    val rightToLeft: Boolean = false,
    val disableZoom: Boolean = false,
    val disableClickScroll: Boolean = false,
    val longPressSaveEnabled: Boolean = false,
    val disablePageAnimation: Boolean = false,
    val snapPages: Boolean = true,
    val isEInk: Boolean = false,
    val epaperThreshold: Int? = null,
    val grayscale: Boolean = false,
    val autoPageSeconds: Int? = null,
    val autoScrollDistance: Int? = null,
)

internal sealed interface MangaScrollCommand {
    val id: Long

    data class Jump(override val id: Long, val index: Int) : MangaScrollCommand

    data class Page(override val id: Long, val direction: Int) : MangaScrollCommand
}

/** Compose owns layout and gestures; callbacks preserve the existing engine's reading order. */
@Composable
internal fun MangaViewportScreen(
    sessionKey: String,
    items: List<MangaReaderItem>,
    bookUrl: String,
    sourceOrigin: String?,
    repository: MangaImageRepository,
    options: MangaViewportOptions,
    colorFilter: MangaColorFilterValues,
    anchorIndex: Int,
    command: MangaScrollCommand?,
    readerActive: Boolean,
    onCurrentItem: (MangaReaderItem) -> Unit,
    onCommandHandled: (Long) -> Unit,
    onMenu: () -> Unit,
    onPageTap: (Int) -> Unit,
    onLongPress: (MangaReaderItem.Page) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    // Session UUID and the three transform floats are the only saved viewport payload.
    val transformState =
        rememberSaveable(
            sessionKey,
            stateSaver =
                listSaver(
                    save = { listOf(it.scale, it.translationX, it.translationY) },
                    restore = { MangaViewportTransform(it[0], it[1], it[2]) },
                ),
        ) {
            mutableStateOf(MangaViewportTransform())
        }
    var transform by transformState
    val latestOptions by rememberUpdatedState(options)
    val latestItems by rememberUpdatedState(items)
    val currentCallback by rememberUpdatedState(onCurrentItem)
    val handledCallback by rememberUpdatedState(onCommandHandled)
    val menuCallback by rememberUpdatedState(onMenu)
    val pageCallback by rememberUpdatedState(onPageTap)
    val longPressCallback by rememberUpdatedState(onLongPress)
    val scope = rememberCoroutineScope()
    val motion = remember(sessionKey) { MangaViewportMotion() }
    val haptic = LocalHapticFeedback.current
    val nextLabel = stringResource(R.string.next_page)
    val previousLabel = stringResource(R.string.prev_page)
    val menuLabel = stringResource(R.string.menu)
    val boundaryColor = colorResource(R.color.book_ant_10)
    LaunchedEffect(options.disableZoom) {
        if (options.disableZoom) {
            motion.animation?.cancel()
            transform = MangaViewportTransform()
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val density = LocalDensity.current
        val widthPx = with(density) { viewportWidth.toPx() }
        val heightPx = with(density) { viewportHeight.toPx() }
        val geometry = MangaViewportGeometry(widthPx, heightPx)
        val latestGeometry by rememberUpdatedState(geometry)
        LaunchedEffect(geometry) { transform = geometry.bounded(transform) }
        key(options.horizontal) {
            val transformation =
                remember(options.epaperThreshold, options.grayscale) {
                    when {
                        options.epaperThreshold != null ->
                            EpaperTransformation(options.epaperThreshold)
                        options.grayscale -> GrayscaleTransformation()
                        else -> null
                    }
                }
            val listState =
                rememberLazyListState(anchorIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)))
            val listHeight =
                if (transform.scale < 1f) viewportHeight / transform.scale else viewportHeight
            LaunchedEffect(listState) {
                snapshotFlow { listState.centerItemIndex() }
                    .distinctUntilChanged()
                    .collect { index -> latestItems.getOrNull(index)?.let(currentCallback) }
            }
            LaunchedEffect(command, listState) {
                when (val request = command) {
                    is MangaScrollCommand.Jump ->
                        if (items.isNotEmpty()) {
                            listState.scrollToItem(request.index.coerceIn(0, items.lastIndex))
                        }
                    is MangaScrollCommand.Page -> {
                        val distance =
                            (if (options.horizontal) widthPx else heightPx) * request.direction
                        if (options.disablePageAnimation) listState.scrollBy(distance)
                        else listState.animateScrollBy(distance)
                    }
                    null -> Unit
                }
                command?.let { handledCallback(it.id) }
            }
            LaunchedEffect(options.autoPageSeconds, readerActive, listState, widthPx, heightPx) {
                val seconds = options.autoPageSeconds ?: return@LaunchedEffect
                if (!readerActive) return@LaunchedEffect
                while (coroutineContext.isActive) {
                    delay(seconds.coerceAtLeast(1) * 1000L)
                    val distance = if (latestOptions.horizontal) widthPx else heightPx
                    if (latestOptions.disablePageAnimation) listState.scrollBy(distance)
                    else listState.animateScrollBy(distance)
                }
            }
            LaunchedEffect(options.autoScrollDistance, readerActive, listState) {
                val speed = options.autoScrollDistance ?: return@LaunchedEffect
                if (!readerActive) return@LaunchedEffect
                var previous = withFrameNanos { it }
                while (coroutineContext.isActive) {
                    val now = withFrameNanos { it }
                    if (!listState.isScrollInProgress) {
                        listState.scrollBy(speed * (now - previous) / 16_000_000f)
                    }
                    previous = now
                }
            }
            val gestureModifier =
                Modifier.fillMaxSize()
                    .testTag("manga-viewport")
                    .semantics {
                        stateDescription = "${transform.scale}×"
                        onClick(menuLabel) {
                            menuCallback()
                            true
                        }
                        customActions =
                            listOf(
                                CustomAccessibilityAction(previousLabel) {
                                    pageCallback(-1)
                                    true
                                },
                                CustomAccessibilityAction(nextLabel) {
                                    pageCallback(1)
                                    true
                                },
                            )
                    }
                    .pointerInput(sessionKey, listState) {
                        mangaTapGestures(
                            options = { latestOptions },
                            geometry = { latestGeometry },
                            transformState = transformState,
                            listState = listState,
                            items = { latestItems },
                            motion = motion,
                            scope = scope,
                            onMenu = { menuCallback() },
                            onPageTap = { pageCallback(it) },
                            onLongPress = { page ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                longPressCallback(page)
                            },
                        )
                    }
                    .pointerInput(sessionKey, listState) {
                        mangaZoomGestures(
                            options = { latestOptions },
                            geometry = { latestGeometry },
                            transformState = transformState,
                            listState = listState,
                            motion = motion,
                            scope = scope,
                        )
                    }
            Box(gestureModifier, contentAlignment = Alignment.Center) {
                val listModifier =
                    Modifier.width(viewportWidth)
                        .requiredHeight(listHeight)
                        .graphicsLayer {
                            scaleX = transform.scale
                            scaleY = transform.scale
                            translationX = transform.translationX
                            translationY = transform.translationY
                        }
                        .testTag("manga-pages")
                val pageContent: @Composable (MangaReaderItem) -> Unit = { item ->
                    Box(Modifier.testTag("manga-item:${item.chapterIndex}:${item.pageIndex}")) {
                        when (item) {
                            is MangaReaderItem.Page ->
                                MangaImageRoute(
                                    request =
                                        MangaImageRequest(
                                            bookUrl,
                                            sourceOrigin,
                                            item.imageUrl,
                                            transformation,
                                        ),
                                    repository = repository,
                                    horizontal = options.horizontal,
                                    isLastImage =
                                        item.imageCount > 0 &&
                                            item.pageIndex == item.imageCount - 1,
                                    viewportWidth = viewportWidth,
                                    viewportHeight = viewportHeight,
                                    colorFilter = colorFilter,
                                    isEInk = options.isEInk,
                                    modifier = Modifier.width(viewportWidth),
                                )
                            is MangaReaderItem.Boundary ->
                                Box(
                                    modifier =
                                        Modifier.width(viewportWidth)
                                            .height(if (item.volume) viewportHeight else 96.dp)
                                            .background(boundaryColor),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(item.message.orEmpty(), color = Color.White)
                                }
                        }
                    }
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    if (options.horizontal) {
                        val flingBehavior =
                            if (
                                options.snapPages &&
                                    options.autoScrollDistance == null &&
                                    !options.disablePageAnimation
                            )
                                rememberSnapFlingBehavior(listState)
                            else ScrollableDefaults.flingBehavior()
                        LazyRow(
                            modifier = listModifier,
                            state = listState,
                            reverseLayout = options.rightToLeft,
                            flingBehavior = flingBehavior,
                        ) {
                            itemsIndexed(items, key = { _, item -> item.viewportKey() }) { _, item
                                ->
                                pageContent(item)
                            }
                            item(key = "footer") { Box(Modifier.width(viewportWidth)) { footer() } }
                        }
                    } else {
                        LazyColumn(modifier = listModifier, state = listState) {
                            itemsIndexed(items, key = { _, item -> item.viewportKey() }) { _, item
                                ->
                                pageContent(item)
                            }
                            item(key = "footer") { footer() }
                        }
                    }
                }
            }
        }
    }
}

private fun MangaReaderItem.viewportKey(): String =
    when (this) {
        is MangaReaderItem.Page -> "page:$chapterIndex:$pageIndex"
        is MangaReaderItem.Boundary -> "boundary:$chapterIndex:$pageIndex"
    }

private fun LazyListState.centerItemIndex(): Int {
    val center = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    return layoutInfo.visibleItemsInfo
        .firstOrNull { center >= it.offset && center < it.offset + it.size }
        ?.index ?: -1
}

private val MangaDecelerate = Easing { fraction -> 1f - (1f - fraction) * (1f - fraction) }

/** Pointer-down cancellation prevents a stale double-tap or fling from replacing a new pinch. */
private class MangaViewportMotion {
    var animation: Job? = null
}

private suspend fun PointerInputScope.mangaTapGestures(
    options: () -> MangaViewportOptions,
    geometry: () -> MangaViewportGeometry,
    transformState: MutableState<MangaViewportTransform>,
    listState: LazyListState,
    items: () -> List<MangaReaderItem>,
    motion: MangaViewportMotion,
    scope: CoroutineScope,
    onMenu: () -> Unit,
    onPageTap: (Int) -> Unit,
    onLongPress: (MangaReaderItem.Page) -> Unit,
) {
    var transform by transformState

    detectTapGestures(
        onTap = { point ->
            when (
                mangaTapAction(
                    point.x / size.width,
                    point.y / size.height,
                    options().horizontal && options().rightToLeft,
                    options().disableClickScroll,
                )
            ) {
                MangaTapAction.Menu -> onMenu()
                MangaTapAction.Next -> onPageTap(1)
                MangaTapAction.Previous -> onPageTap(-1)
                MangaTapAction.None -> Unit
            }
        },
        onDoubleTap = { point ->
            if (!options().disableZoom) {
                val start = transform
                val target =
                    geometry()
                        .doubleTap(
                            start,
                            point.x - size.width / 2f,
                            point.y - size.height / 2f,
                        )
                motion.animation?.cancel()
                motion.animation = scope.launch {
                    animate(
                        0f,
                        1f,
                        animationSpec = tween(200, easing = MangaDecelerate),
                    ) { progress, _ ->
                        transform =
                            MangaViewportTransform(
                                scale = start.scale + (target.scale - start.scale) * progress,
                                translationX =
                                    start.translationX +
                                        (target.translationX - start.translationX) * progress,
                                translationY =
                                    start.translationY +
                                        (target.translationY - start.translationY) * progress,
                            )
                    }
                }
            }
        },
        onLongPress = { point ->
            if (!options().longPressSaveEnabled) return@detectTapGestures
            val coordinate =
                if (options().horizontal) {
                    (point.x - size.width / 2f - transform.translationX) / transform.scale +
                        size.width / 2f
                } else {
                    val unscaledHeight =
                        if (transform.scale < 1f) size.height / transform.scale
                        else size.height.toFloat()
                    (point.y - size.height / 2f - transform.translationY) / transform.scale +
                        unscaledHeight / 2f
                }
            val index =
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull {
                        coordinate >= it.offset && coordinate < it.offset + it.size
                    }
                    ?.index
            (index?.let { items().getOrNull(it) } as? MangaReaderItem.Page)?.let { page ->
                onLongPress(page)
            }
        },
    )
}

private suspend fun PointerInputScope.mangaZoomGestures(
    options: () -> MangaViewportOptions,
    geometry: () -> MangaViewportGeometry,
    transformState: MutableState<MangaViewportTransform>,
    listState: LazyListState,
    motion: MangaViewportMotion,
    scope: CoroutineScope,
) {
    var transform by transformState

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        motion.animation?.cancel()
        val tracker = VelocityTracker()
        var position = down.position
        tracker.addPosition(down.uptimeMillis, position)
        var multiplePointers = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val count = event.changes.count { it.pressed }
            multiplePointers = multiplePointers || count > 1
            if (!options().disableZoom) {
                val pan = event.calculatePan()
                if (count > 1) {
                    val focal = event.calculateCentroid(useCurrent = false)
                    transform =
                        geometry()
                            .pinch(
                                transform,
                                event.calculateZoom(),
                                focal.x - size.width / 2f,
                                focal.y - size.height / 2f,
                            )
                    event.changes.forEach { if (it.pressed) it.consume() }
                } else if (transform.scale > 1f) {
                    // Preserve ordinary list scrolling while panning the
                    // enlarged viewport.
                    val edge = !listState.canScrollBackward || !listState.canScrollForward
                    transform =
                        geometry()
                            .pan(
                                transform,
                                pan.x,
                                if (edge) pan.y else 0f,
                            )
                }
                position += pan
                tracker.addPosition(
                    event.changes.first().uptimeMillis,
                    position,
                )
            }
        } while (event.changes.any { it.pressed })
        if (!multiplePointers && transform.scale > 1f && !options().disableZoom) {
            val velocity = tracker.calculateVelocity()
            val start = transform
            val edge = !listState.canScrollBackward || !listState.canScrollForward
            if (Offset(velocity.x, velocity.y).getDistance() <= 1f) return@awaitEachGesture
            motion.animation = scope.launch {
                animate(
                    0f,
                    1f,
                    animationSpec = tween(200, easing = MangaDecelerate),
                ) { progress, _ ->
                    transform =
                        geometry()
                            .pan(
                                start,
                                velocity.x * .2f * progress,
                                if (edge) velocity.y * .2f * progress else 0f,
                            )
                }
            }
        }
    }
}
