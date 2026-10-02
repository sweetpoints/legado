package io.legado.app.data.repository

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Detached, bounded previews. Unlike Config.curBgDrawable this never changes reader-wide isNineBgImg. */
interface ReaderBackgroundPreviewRepository {
    suspend fun load(configuration: String, width: Int, height: Int): Drawable
}
class AppReaderBackgroundPreviewRepository(context: Context) : ReaderBackgroundPreviewRepository {
    private val context = context.applicationContext
    override suspend fun load(configuration: String, width: Int, height: Int): Drawable = withContext(Dispatchers.IO) {
        val fallback = context.getCompatColor(R.color.background).toDrawable()
        if (width <= 0 || height <= 0) return@withContext fallback
        val config = GSON.fromJsonObject<ReadBookConfig.Config>(configuration).getOrNull() ?: return@withContext fallback
        val path = config.curBgStr()
        try {
            when (config.curBgType()) {
                0 -> path.toColorInt().toDrawable()
                1 -> BitmapUtils.decodeAssetsBitmap(context, "bg${File.separator}$path", width, height)
                    ?.resizeAndRecycle(width, height)?.toDrawable(context.resources) ?: fallback
                else -> {
                    val file = if (path.contains(File.separator)) path else FileUtils.getPath(context.externalFiles, "bg", path)
                    if (path.endsWith(".9.png")) BitmapUtils.decodeNinePatchDrawable(file) ?: fallback
                    else BitmapUtils.decodeBitmap(file, width, height)?.resizeAndRecycle(width, height)?.toDrawable(context.resources) ?: fallback
                }
            }
        } catch (_: OutOfMemoryError) { fallback } catch (_: Exception) { fallback }
    }
}
