package io.legado.app.ui.widget.dialog.photo

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun PhotoScreen(
    state: PhotoUiState,
    onClose: () -> Unit,
    isEInk: Boolean = false,
    modifier: Modifier = Modifier,
    imageKey: String = "",
) {
    val bitmap = state.bitmap
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val painter = remember(state.animation) { state.animation?.let(::PhotoAnimatedPainter) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(painter, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> painter?.start()
                Lifecycle.Event.ON_STOP -> painter?.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) painter?.start()
        onDispose {
            lifecycle.removeObserver(observer)
            painter?.stop()
        }
    }
    val imageWidth = image?.width?.toFloat() ?: painter?.intrinsicSize?.width ?: 0f
    val imageHeight = image?.height?.toFloat() ?: painter?.intrinsicSize?.height ?: 0f
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var transform by
        rememberSaveable(
            imageKey,
            stateSaver =
                listSaver(
                    save = { listOf(it.scale, it.offsetX, it.offsetY) },
                    restore = { PhotoTransform(it[0], it[1], it[2]) },
                ),
        ) {
            mutableStateOf(PhotoTransform())
        }
    val geometry =
        PhotoGeometry(
            imageWidth,
            imageHeight,
            viewport.width.toFloat(),
            viewport.height.toFloat(),
        )
    val latestGeometry by rememberUpdatedState(geometry)
    LaunchedEffect(geometry) {
        if (imageWidth > 0 && imageHeight > 0 && viewport.width > 0 && viewport.height > 0)
            transform = geometry.bounded(transform)
    }
    val close by rememberUpdatedState(onClose)
    val closeLabel = stringResource(R.string.close)
    val plusLabel = stringResource(R.string.plus)
    val minusLabel = stringResource(R.string.reduce)
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .background(
                if (isEInk) MaterialTheme.colorScheme.background
                else colorResource(R.color.photo_viewer_scrim)
            )
            .onSizeChanged { viewport = it }
            .testTag("photo-viewer")
            .semantics {
                stateDescription = "${transform.scale}×"
                onClick(closeLabel) {
                    close()
                    true
                }
                customActions =
                    listOf(
                        CustomAccessibilityAction(plusLabel) {
                            transform = latestGeometry.transform(transform, 1.5f, 0f, 0f, 0f, 0f)
                            true
                        },
                        CustomAccessibilityAction(minusLabel) {
                            transform =
                                latestGeometry.transform(transform, 1f / 1.5f, 0f, 0f, 0f, 0f)
                            true
                        },
                    )
            }
            .pointerInput(bitmap, state.animation) {
                detectTapGestures(
                    onTap = { close() },
                    onDoubleTap = {
                        transform =
                            latestGeometry.doubleTap(
                                transform,
                                it.x - size.width / 2f,
                                it.y - size.height / 2f,
                            )
                    },
                )
            }
            .pointerInput(bitmap, state.animation) {
                coroutineScope {
                    var fling: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fling?.cancel()
                        val tracker = VelocityTracker()
                        var tracked = down.position
                        tracker.addPosition(down.uptimeMillis, tracked)
                        var moved = false
                        var multiTouch = false
                        var cumulativeZoom = 1f
                        var cumulativePan = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            multiTouch = multiTouch || event.changes.count { it.pressed } > 1
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (event.changes.any { it.isConsumed }) break
                            if (!moved) {
                                cumulativePan += pan
                                cumulativeZoom *= zoom
                                moved =
                                    cumulativePan.getDistance() > viewConfiguration.touchSlop ||
                                        abs(1f - cumulativeZoom) *
                                            event.calculateCentroidSize(useCurrent = false) >
                                            viewConfiguration.touchSlop
                            }
                            if (moved) {
                                val focal = event.calculateCentroid(useCurrent = false)
                                if (focal.x.isFinite() && focal.y.isFinite())
                                    transform =
                                        latestGeometry.transform(
                                            transform,
                                            zoom,
                                            pan.x,
                                            pan.y,
                                            focal.x - size.width / 2f,
                                            focal.y - size.height / 2f,
                                        )
                                event.changes.forEach {
                                    if (it.pressed && it.position != it.previousPosition)
                                        it.consume()
                                }
                            }
                            tracked += pan
                            tracker.addPosition(event.changes.first().uptimeMillis, tracked)
                        } while (event.changes.any { it.pressed })
                        if (moved && !multiTouch) {
                            val velocity = tracker.calculateVelocity()
                            val vector = Offset(velocity.x, velocity.y)
                            val speed = vector.getDistance()
                            if (speed > 1f)
                                fling = launch {
                                    var previous = 0f
                                    AnimationState(initialValue = 0f, initialVelocity = speed)
                                        .animateDecay(exponentialDecay<Float>()) {
                                            val delta = vector / speed * (value - previous)
                                            previous = value
                                            val before = transform
                                            transform =
                                                latestGeometry.transform(
                                                    transform,
                                                    1f,
                                                    delta.x,
                                                    delta.y,
                                                    0f,
                                                    0f,
                                                )
                                            if (transform == before) cancelAnimation()
                                        }
                                }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null || painter != null)
            Canvas(Modifier.fillMaxSize().testTag("photo-image")) {
                withTransform({
                    translate(
                        size.width / 2f + transform.offsetX,
                        size.height / 2f + transform.offsetY,
                    )
                    scale(
                        latestGeometry.fitScale * transform.scale,
                        latestGeometry.fitScale * transform.scale,
                        Offset.Zero,
                    )
                    translate(-imageWidth / 2f, -imageHeight / 2f)
                }) {
                    if (image != null) drawImage(image)
                    else if (painter != null) with(painter) { draw(Size(imageWidth, imageHeight)) }
                }
            }
        if (state.isLoading) CircularProgressIndicator(Modifier.testTag("photo-loading"))
        state.error?.let {
            Text(
                it,
                color =
                    if (isEInk) MaterialTheme.colorScheme.onBackground
                    else androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}
