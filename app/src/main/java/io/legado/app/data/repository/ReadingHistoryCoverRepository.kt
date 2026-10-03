package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import io.legado.app.data.image.CoverImage
import io.legado.app.data.image.CoverLoadResult
import io.legado.app.data.image.GlideCoverImageLoader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ReadingHistoryCoverResult(val image: CoverImage, val placeholder: Boolean)
interface ReadingHistoryCoverRepository {
    suspend fun load(cover: ReadingHistoryCover, fallback: String?, width: Int, height: Int): ReadingHistoryCoverResult
}

/** History has its own day/night fallback; it never generates a title on a failed cover. */
class GlideReadingHistoryCoverRepository(context: Context) : ReadingHistoryCoverRepository {
    private val loader = GlideCoverImageLoader(context.applicationContext)
    private val white = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    private val configuration = CoverConfiguration(white, drawName = false, drawAuthor = false)
    override suspend fun load(cover: ReadingHistoryCover, fallback: String?, width: Int, height: Int): ReadingHistoryCoverResult {
        val primary = listOfNotNull(cover.current?.takeIf(String::isNotBlank), cover.snapshot?.takeIf(String::isNotBlank)).distinct()
        for (path in primary) {
            candidate(CoverRequest(path = path, loadOnlyWifi = cover.onlyWifi, sourceOrigin = cover.sourceOrigin), width, height)?.let { return it }
        }
        fallback?.takeIf(String::isNotBlank)?.let { path ->
            // The previous fallback request intentionally had no Wi-Fi or source rule options.
            candidate(CoverRequest(path = path), width, height)?.let { return it }
        }
        currentCoroutineContext().ensureActive()
        return ReadingHistoryCoverResult(CoverImage.Static(white), true)
    }
    private suspend fun candidate(request: CoverRequest, width: Int, height: Int): ReadingHistoryCoverResult? {
        val result: CoverLoadResult = loader.load(request, configuration, width, height)
        try {
            currentCoroutineContext().ensureActive()
            return if (result.needsTitle) null else ReadingHistoryCoverResult(result.image, false)
        } catch (error: Throwable) {
            when (val image = result.image) {
                is CoverImage.Animated -> image.resource.release()
                is CoverImage.Static -> if (image.bitmap !== white) image.bitmap.recycle()
            }
            throw error
        }
    }
}
