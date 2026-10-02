package io.legado.app.ui.components.image

import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.image.ManagedDrawableCallback

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/** Drawable callbacks invalidate Compose's draw phase without embedding an Android View. */
internal class LifecycleDrawablePainter(private val image: AnimatedDrawableResource) : Painter(), ManagedDrawableCallback {
    private val drawable = image.drawable
    private val handler = Handler(Looper.getMainLooper())
    private val frameRevision = mutableIntStateOf(0)
    override val intrinsicSize: Size = Size(drawable.intrinsicWidth.coerceAtLeast(1).toFloat(), drawable.intrinsicHeight.coerceAtLeast(1).toFloat())

    fun start() {
        if (image.isReleased) return
        drawable.callback = this
        (drawable as? Animatable)?.start()
    }

    override fun stop() {
        (drawable as? Animatable)?.stop()
        handler.removeCallbacksAndMessages(this)
        if (drawable.callback === this) drawable.callback = null
    }

    override fun invalidateDrawable(who: Drawable) {
        if (!image.isReleased && drawable.callback === this) frameRevision.intValue++
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        if (!image.isReleased) handler.postAtTime(what, this, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what) }

    override fun DrawScope.onDraw() {
        frameRevision.intValue // Observe frames only in the draw phase.
        if (image.isReleased) return
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}
