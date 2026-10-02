package io.legado.app.ui.widget.dialog.photo

import kotlin.math.max
import kotlin.math.min

/** Coordinates are in viewport pixels, relative to the viewport's center. */
data class PhotoTransform(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

data class PhotoGeometry(val imageWidth: Float, val imageHeight: Float, val viewportWidth: Float, val viewportHeight: Float) {
    val fitScale: Float get() = if (imageWidth > 0f && imageHeight > 0f && viewportWidth > 0f && viewportHeight > 0f)
        min(viewportWidth / imageWidth, viewportHeight / imageHeight) else 1f

    fun bounded(transform: PhotoTransform): PhotoTransform {
        val scale = transform.scale.takeIf { it.isFinite() }?.coerceIn(1f, MAX_PHOTO_SCALE) ?: 1f
        val maxX = max(0f, (imageWidth * fitScale * scale - viewportWidth) / 2f)
        val maxY = max(0f, (imageHeight * fitScale * scale - viewportHeight) / 2f)
        return PhotoTransform(scale, transform.offsetX.coerceIn(-maxX, maxX), transform.offsetY.coerceIn(-maxY, maxY))
    }

    fun transform(current: PhotoTransform, zoom: Float, panX: Float, panY: Float, focalX: Float, focalY: Float): PhotoTransform {
        if (!zoom.isFinite() || zoom <= 0f) return current
        val scale = (current.scale * zoom).coerceIn(1f, MAX_PHOTO_SCALE)
        val ratio = scale / current.scale
        return bounded(PhotoTransform(scale,
            focalX - (focalX - current.offsetX) * ratio + panX,
            focalY - (focalY - current.offsetY) * ratio + panY))
    }

    fun doubleTap(current: PhotoTransform, focalX: Float, focalY: Float): PhotoTransform =
        if (current.scale > 1f) PhotoTransform() else transform(current, MAX_PHOTO_SCALE, 0f, 0f, focalX, focalY)
}

const val MAX_PHOTO_SCALE = 2.5f
