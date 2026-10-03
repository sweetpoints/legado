package io.legado.app.ui.widget.dialog.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.help.book.BookHelp
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

data class PhotoRequest(
    val src: String,
    val sourceOrigin: String? = null,
    val isBook: Boolean = false,
)

fun interface PhotoImageLoader {
    suspend fun load(request: PhotoRequest): PhotoImage
}

class GlidePhotoImageLoader(context: Context) : PhotoImageLoader {
    private val context = context.applicationContext

    override suspend fun load(request: PhotoRequest): PhotoImage {
        var target: FutureTarget<Drawable>? = null
        var ownedBitmap: Bitmap? = null
        var animation: PhotoImage.Animated? = null
        var delivered = false
        try {
            val image =
                withContext(Dispatchers.IO) {
                    val cached =
                        synchronized(ImageProvider.bitmapLruCache) {
                            runCatching {
                                ImageProvider.get(request.src)?.copy(Bitmap.Config.ARGB_8888, false)
                            }
                                .getOrNull()
                        }
                    if (cached != null) {
                        ownedBitmap = cached
                        return@withContext PhotoImage.Static(cached)
                    }
                    val file =
                        if (request.isBook)
                            ReadBook.book?.let { BookHelp.getImage(it, request.src) }
                        else null
                    val local = file?.exists() == true
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            target =
                                ImageLoader.load(
                                        context,
                                        if (local) file!!.absolutePath else request.src,
                                    )
                                    .apply {
                                        if (!local)
                                            request.sourceOrigin?.let {
                                                apply(
                                                    RequestOptions()
                                                        .set(
                                                            OkHttpModelLoader.sourceOriginOption,
                                                            it,
                                                        )
                                                )
                                            }
                                        if (local) diskCacheStrategy(DiskCacheStrategy.NONE)
                                    }
                                    .dontTransform()
                                    .downsample(DownsampleStrategy.NONE)
                                    .submit()
                        }
                        val requestTarget = checkNotNull(target)
                        val resource = runInterruptible { requestTarget.get() }
                        coroutineContext.ensureActive()
                        if (resource is Animatable) {
                            PhotoImage.Animated(resource) {
                                    Glide.with(context).clear(requestTarget)
                                }
                                .also { animation = it }
                        } else {
                            val bitmap =
                                if (resource is BitmapDrawable)
                                    checkNotNull(
                                        resource.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    )
                                else rasterize(resource)
                            ownedBitmap = bitmap
                            PhotoImage.Static(bitmap)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        coroutineContext.ensureActive()
                        val fallback =
                            if (request.isBook && !local) BookCover.defaultDrawable
                            else
                                checkNotNull(
                                    AppCompatResources.getDrawable(
                                        context,
                                        R.drawable.image_loading_error,
                                    )
                                )
                        val bitmap = rasterize(fallback)
                        ownedBitmap = bitmap
                        PhotoImage.Static(bitmap)
                    }
                }
            coroutineContext.ensureActive()
            delivered = true
            return image
        } finally {
            if (animation == null || !delivered) {
                animation?.release()
                    ?: withContext(NonCancellable + Dispatchers.Main.immediate) {
                        target?.let { Glide.with(context).clear(it) }
                    }
            }
            if (!delivered) ownedBitmap?.recycle()
        }
    }

    private fun rasterize(source: Drawable): Bitmap {
        val drawable = source.constantState?.newDrawable(context.resources)?.mutate() ?: source
        val bitmap =
            Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val previousBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        } finally {
            drawable.bounds = previousBounds
        }
        return bitmap
    }
}
