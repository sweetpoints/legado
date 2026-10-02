package io.legado.app.ui.widget.dialog.photo

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PhotoImage {
    class Static(val bitmap: Bitmap) : PhotoImage

    /** Keeps Glide's animation resource leased until it has stopped drawing. */
    class Animated(val drawable: Drawable, private val clearRequest: () -> Unit) : PhotoImage {
        private val released = AtomicBoolean(false)
        val isReleased: Boolean get() = released.get()

        suspend fun release() {
            if (!released.compareAndSet(false, true)) return
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                // No callback or scheduled painter work may outlive the pooled frame resource.
                (drawable.callback as? PhotoAnimatedPainter)?.stop()
                (drawable as? Animatable)?.stop()
                drawable.callback = null
                clearRequest()
            }
        }
    }
}
