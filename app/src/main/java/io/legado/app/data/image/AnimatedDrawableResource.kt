package io.legado.app.data.image

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A callback owning scheduled draw work must stop that work before its drawable is released. */
interface ManagedDrawableCallback : Drawable.Callback {
    fun stop()
}

/** Keeps a pooled animation resource leased until all drawing and callbacks have stopped. */
open class AnimatedDrawableResource(val drawable: Drawable, private val clearRequest: () -> Unit) {
    private val released = AtomicBoolean(false)
    val isReleased: Boolean
        get() = released.get()

    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            (drawable.callback as? ManagedDrawableCallback)?.stop()
            (drawable as? Animatable)?.stop()
            drawable.callback = null
            clearRequest()
        }
    }
}
