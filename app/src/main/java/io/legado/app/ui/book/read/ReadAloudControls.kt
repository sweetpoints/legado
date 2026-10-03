package io.legado.app.ui.book.read

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import kotlin.math.roundToInt

/**
 * Reader-only controls; positions are fractions of the safe viewport, so rotation stays reachable.
 */
class ReadAloudControls(
    private val bar: ComposeView,
    private val pause: () -> Unit,
    private val back: () -> Unit,
    private val readHere: () -> Unit,
    private val refresh: () -> Unit,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val context = bar.context
    private val prefs = context.defaultSharedPreferences
    private val parent
        get() = bar.parent as ViewGroup

    private var menuVisible = false
    private var hidden = false
    private var movement = 0f
    private var wasFollowing = true
    private var running = false
    private var dragging = false
    private var presentation by mutableStateOf(ReadAloudControlPresentation())
    private val layoutListener =
        View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (view === parent && right - left != oldRight - oldLeft && bar.isVisible) {
                updateSize(presentation.showPause)
            }
            if (!dragging) position()
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        bar.addOnLayoutChangeListener(layoutListener)
        parent.addOnLayoutChangeListener(layoutListener)
        bar.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        bar.setContent {
            LegadoComposeTheme {
                ReadAloudControlsScreen(
                    presentation,
                    pause,
                    { ReadAloud.stop(context) },
                    back,
                    readHere,
                    ::beginDrag,
                    ::drag,
                    { endDrag(true) },
                    { endDrag(false) },
                )
            }
        }
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
        bar.isVisible = shouldShow
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
                (parent.width - 32.dpToPx()).coerceAtLeast(85.dpToPx()),
            )
        val height = (width / 6f).roundToInt().coerceAtLeast(1)
        bar.updateLayoutParams<FrameLayout.LayoutParams> {
            this.width = if (showPause) height else width
            this.height = height
        }
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
        val insets =
            ViewCompat.getRootWindowInsets(parent)
                ?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
        val left = (insets?.left ?: 0).toFloat()
        val top = (insets?.top ?: 0).toFloat()
        return floatArrayOf(
            left,
            top,
            (parent.width - (insets?.right ?: 0) - bar.width).toFloat().coerceAtLeast(left),
            (parent.height - (insets?.bottom ?: 0) - bar.height).toFloat().coerceAtLeast(top),
        )
    }

    private fun position() {
        if (bar.width == 0 || parent.height == 0 || dragging) return
        val (left, top, right, bottom) = safeBounds()
        val dock = prefs.getBoolean(PreferKey.readAloudControlsDock, false)
        // Keep the last manually chosen position when dragging is disabled.
        var x = prefs.getFloat(PreferKey.readAloudControlsX, .5f)
        x = if (x.isFinite()) x.coerceIn(0f, 1f) else .5f
        if (dock) x = if (x < .5f) 0f else 1f
        val y = prefs.getFloat(PreferKey.readAloudControlsY, -1f)
        bar.x = left + (right - left) * x
        bar.y =
            if (y.isFinite() && y >= 0f) top + (bottom - top) * y.coerceIn(0f, 1f)
            else (bottom - 24.dpToPx()).coerceAtLeast(top)
    }

    private fun beginDrag() {
        dragging = true
        parent.requestDisallowInterceptTouchEvent(true)
    }

    private fun drag(dx: Float, dy: Float) {
        val (left, top, right, bottom) = safeBounds()
        bar.x = (bar.x + dx).coerceIn(left, right)
        bar.y = (bar.y + dy).coerceIn(top, bottom)
    }

    private fun endDrag(save: Boolean) {
        if (save && dragging) {
            val (left, top, right, bottom) = safeBounds()
            prefs
                .edit()
                .putFloat(
                    PreferKey.readAloudControlsX,
                    (bar.x - left) / (right - left).coerceAtLeast(1f),
                )
                .putFloat(
                    PreferKey.readAloudControlsY,
                    (bar.y - top) / (bottom - top).coerceAtLeast(1f),
                )
                .apply()
        }
        dragging = false
        parent.requestDisallowInterceptTouchEvent(false)
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
        bar.removeOnLayoutChangeListener(layoutListener)
        parent.removeOnLayoutChangeListener(layoutListener)
        bar.disposeComposition()
    }
}

internal fun readAloudControlWidth(prefs: SharedPreferences): Int =
    prefs
        .getInt(
            PreferKey.readAloudControlsWidth,
            prefs.getInt(PreferKey.readAloudControlsSize, 48).coerceIn(48, 72) * 6,
        )
        .coerceIn(85, 432)
