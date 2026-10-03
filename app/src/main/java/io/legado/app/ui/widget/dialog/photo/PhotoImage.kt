package io.legado.app.ui.widget.dialog.photo

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import io.legado.app.data.image.AnimatedDrawableResource

sealed interface PhotoImage {
    class Static(val bitmap: Bitmap) : PhotoImage

    /** Adapter retaining Photo's public API; resource ownership lives in the data layer. */
    class Animated(drawable: Drawable, clearRequest: () -> Unit) :
        AnimatedDrawableResource(drawable, clearRequest), PhotoImage
}
