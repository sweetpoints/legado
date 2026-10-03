package io.legado.app.ui.book.manga

import kotlin.math.max

internal data class MangaViewportTransform(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
)

/** The manga engine zooms the viewport, including boundaries, between 0.5x and 3x. */
internal data class MangaViewportGeometry(val width: Float, val height: Float) {
    fun bounded(transform: MangaViewportTransform): MangaViewportTransform {
        val scale = transform.scale.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f
        val maximumX = max(0f, width * (scale - 1f) / 2f)
        val maximumY = max(0f, height * (scale - 1f) / 2f)
        return MangaViewportTransform(
            scale = scale,
            translationX =
                transform.translationX.takeIf { it.isFinite() }?.coerceIn(-maximumX, maximumX)
                    ?: 0f,
            translationY =
                transform.translationY.takeIf { it.isFinite() }?.coerceIn(-maximumY, maximumY)
                    ?: 0f,
        )
    }

    fun pinch(
        current: MangaViewportTransform,
        zoom: Float,
        focalX: Float,
        focalY: Float,
    ): MangaViewportTransform {
        if (!zoom.isFinite() || zoom <= 0f) return current
        val nextScale = (current.scale * zoom).coerceIn(.5f, 3f)
        val ratio = nextScale / current.scale
        return bounded(
            MangaViewportTransform(
                scale = nextScale,
                translationX = focalX - (focalX - current.translationX) * ratio,
                translationY = focalY - (focalY - current.translationY) * ratio,
            )
        )
    }

    fun pan(current: MangaViewportTransform, x: Float, y: Float): MangaViewportTransform =
        bounded(
            current.copy(
                translationX = current.translationX + x,
                translationY = current.translationY + y,
            )
        )

    fun doubleTap(
        current: MangaViewportTransform,
        focalX: Float,
        focalY: Float,
    ): MangaViewportTransform =
        if (current.scale != 1f) MangaViewportTransform() else pinch(current, 2f, focalX, focalY)
}
