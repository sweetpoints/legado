package io.legado.app.ui.book.read

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import kotlin.math.roundToInt

/** Floating controls composed directly over the canvas, with safe viewport fraction persistence. */
class ReadAloudComposeControls(
    private val context: Context,
    private val pause: () -> Unit,
    private val back: () -> Unit,
    private val readHere: () -> Unit,
    private val refresh: () -> Unit,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs = context.defaultSharedPreferences
    private var menuVisible = false
    private var hidden = false
    private var movement = 0f
    private var wasFollowing = true
    private var running = false
    private var dragging = false
    private var presentation by mutableStateOf(ReadAloudControlPresentation())
    var visible by mutableStateOf(false)
        private set

    private var x by mutableStateOf(0f)
    private var y by mutableStateOf(0f)
    private var barWidth by mutableStateOf(1)
    private var barHeight by mutableStateOf(1)
    private var viewport = ReaderControlsViewport()
    val bounds: ReadAloudControlsBounds
        get() = ReadAloudControlsBounds(x, y, barWidth, barHeight)

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    @Composable
    fun Content() {
        if (!visible) return
        val density = LocalDensity.current
        Box(
            Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(with(density) { barWidth.toDp() }, with(density) { barHeight.toDp() })
        ) {
            ReadAloudControlsScreen(
                state = presentation,
                pause = pause,
                stop = { ReadAloud.stop(context) },
                back = back,
                readHere = readHere,
                dragStart = ::beginDrag,
                drag = ::drag,
                dragEnd = { endDrag(true) },
                dragCancel = { endDrag(false) },
            )
        }
    }

    fun updateViewport(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val next = ReaderControlsViewport(width, height, left, top, right, bottom)
        if (viewport == next) return
        viewport = next
        updateSize(presentation.showPause)
        position()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key?.startsWith("readAloudControls") == true) {
            if (key != PreferKey.readAloudControlsX && key != PreferKey.readAloudControlsY) reveal()
            else position()
        }
    }

    fun update(menuVisible: Boolean) {
        this.menuVisible = menuVisible
        val following = ReadAloud.followReadAloudPosition
        val isRun = BaseReadAloudService.isRun
        if (isRun != running || following && !wasFollowing) {
            hidden = false
            movement = 0f
        }
        running = isRun
        wasFollowing = following
        val showPause = following && prefs.getBoolean(PreferKey.readAloudControlsPause, true)
        val shouldShow =
            ReadAloudBarVisibility.shouldShow(
                isRun,
                following,
                menuVisible,
                showPause,
                prefs.getBoolean(PreferKey.readAloudControlsPosition, true),
            ) && !hidden
        visible = shouldShow
        if (!shouldShow) {
            endDrag(save = false)
            return
        }
        val background = context.bottomBackground
        val foreground = context.getPrimaryTextColor(ColorUtils.isColorLight(background))
        presentation =
            presentation.copy(
                showPause = showPause,
                paused = BaseReadAloudService.pause,
                drag = prefs.getBoolean(PreferKey.readAloudControlsDrag, false),
                background =
                    Color(
                        ColorUtils.withAlpha(
                            background,
                            prefs.getInt(PreferKey.readAloudControlsOpacity, 90).coerceIn(0, 100) /
                                100f,
                        )
                    ),
                foreground = Color(foreground),
                borderAlpha = if (AppConfig.isEInkMode) 1f else .25f,
            )
        updateSize(showPause)
        position()
    }

    private fun updateSize(showPause: Boolean) {
        val width =
            minOf(
                readAloudControlWidth(prefs).dpToPx(),
                (viewport.width - 32.dpToPx()).coerceAtLeast(85.dpToPx()),
            )
        val height = (width / 6f).roundToInt().coerceAtLeast(1)
        barWidth = if (showPause) height else width
        barHeight = height
        presentation = presentation.copy(width = width / context.resources.displayMetrics.density)
    }

    fun onMovement(percentOfPage: Float) {
        if (
            !BaseReadAloudService.isRun ||
                ReadAloud.followReadAloudPosition ||
                menuVisible ||
                hidden ||
                !prefs.getBoolean(PreferKey.readAloudControlsAutoHide, false) ||
                percentOfPage <= 0f
        )
            return
        movement += percentOfPage
        if (movement >= prefs.getInt(PreferKey.readAloudControlsThreshold, 100).coerceIn(10, 500)) {
            hidden = true
            refresh()
        }
    }

    fun reveal(resetPosition: Boolean = false) {
        hidden = false
        movement = 0f
        if (resetPosition)
            prefs
                .edit()
                .remove(PreferKey.readAloudControlsX)
                .remove(PreferKey.readAloudControlsY)
                .apply()
        refresh()
    }

    private fun safeBounds(): FloatArray {
        val left = viewport.left.toFloat()
        val top = viewport.top.toFloat()
        return floatArrayOf(
            left,
            top,
            (viewport.width - viewport.right - barWidth).toFloat().coerceAtLeast(left),
            (viewport.height - viewport.bottom - barHeight).toFloat().coerceAtLeast(top),
        )
    }

    private fun position() {
        if (barWidth == 0 || viewport.height == 0 || dragging) return
        val (left, top, right, bottom) = safeBounds()
        val dock = prefs.getBoolean(PreferKey.readAloudControlsDock, false)
        // Keep the last manually chosen position when dragging is disabled.
        var fractionX = prefs.getFloat(PreferKey.readAloudControlsX, .5f)
        fractionX = if (fractionX.isFinite()) fractionX.coerceIn(0f, 1f) else .5f
        if (dock) fractionX = if (fractionX < .5f) 0f else 1f
        val fractionY = prefs.getFloat(PreferKey.readAloudControlsY, -1f)
        x = left + (right - left) * fractionX
        y =
            if (fractionY.isFinite() && fractionY >= 0f)
                top + (bottom - top) * fractionY.coerceIn(0f, 1f)
            else (bottom - 24.dpToPx()).coerceAtLeast(top)
    }

    private fun beginDrag() {
        dragging = true
    }

    private fun drag(dx: Float, dy: Float) {
        val (left, top, right, bottom) = safeBounds()
        x = (x + dx).coerceIn(left, right)
        y = (y + dy).coerceIn(top, bottom)
    }

    private fun endDrag(save: Boolean) {
        if (save && dragging) {
            val (left, top, right, bottom) = safeBounds()
            prefs
                .edit()
                .putFloat(
                    PreferKey.readAloudControlsX,
                    (x - left) / (right - left).coerceAtLeast(1f),
                )
                .putFloat(
                    PreferKey.readAloudControlsY,
                    (y - top) / (bottom - top).coerceAtLeast(1f),
                )
                .apply()
        }
        dragging = false
        position()
    }

    fun save() =
        Bundle().apply {
            putBoolean("hidden", hidden)
            putFloat("movement", movement)
        }

    fun restore(state: Bundle?) {
        hidden = state?.getBoolean("hidden") ?: false
        movement = state?.getFloat("movement") ?: 0f
        running = BaseReadAloudService.isRun
        wasFollowing = ReadAloud.followReadAloudPosition
    }

    fun dispose() {
        endDrag(save = false)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }
}

private data class ReaderControlsViewport(
    val width: Int = 0,
    val height: Int = 0,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

/** Read-only pixel geometry for native integration and rendered UI verification. */
data class ReadAloudControlsBounds(val x: Float, val y: Float, val width: Int, val height: Int)

internal fun readAloudControlWidth(prefs: SharedPreferences): Int =
    prefs
        .getInt(
            PreferKey.readAloudControlsWidth,
            prefs.getInt(PreferKey.readAloudControlsSize, 48).coerceIn(48, 72) * 6,
        )
        .coerceIn(85, 432)
