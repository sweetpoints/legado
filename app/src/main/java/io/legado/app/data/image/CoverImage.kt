package io.legado.app.data.image

import android.graphics.Bitmap

sealed interface CoverImage {
    class Static(val bitmap: Bitmap) : CoverImage

    class Animated(val resource: AnimatedDrawableResource) : CoverImage
}

data class CoverLoadResult(val image: CoverImage, val needsTitle: Boolean)
