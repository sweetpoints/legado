package io.legado.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestOptions
import io.legado.app.data.repository.CoverConfiguration
import io.legado.app.data.repository.CoverRequest
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

/** Uses the registered Legado URL/source/decryption loaders and retains animated resources. */
class GlideCoverImageLoader(context: Context) {
    private val context = context.applicationContext
    suspend fun load(request: CoverRequest, configuration: CoverConfiguration, width: Int, height: Int): CoverLoadResult {
        require(width > 0 && height > 0)
        if (configuration.useDefault || request.normalizedPath == null)
            return CoverLoadResult(CoverImage.Static(configuration.defaultBitmap), true)
        var target: FutureTarget<Drawable>? = null
        var ownedBitmap: Bitmap? = null
        var animation: AnimatedDrawableResource? = null
        var delivered = false
        try {
            val image = withContext(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main.immediate) {
                        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, request.loadOnlyWifi)
                        request.sourceOrigin?.let { options = options.set(OkHttpModelLoader.sourceOriginOption, it) }
                        target = ImageLoader.load(context, request.normalizedPath).apply(options).centerCrop().submit(width, height)
                    }
                    val requestTarget = checkNotNull(target)
                    val drawable = runInterruptible { requestTarget.get() }
                    coroutineContext.ensureActive()
                    if (drawable is Animatable) {
                        val resource = AnimatedDrawableResource(drawable) { Glide.with(context).clear(requestTarget) }
                        animation = resource
                        CoverLoadResult(CoverImage.Animated(resource), false)
                    } else {
                        val bitmap = if (drawable is BitmapDrawable) checkNotNull(drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)) else {
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                drawable.setBounds(0, 0, width, height); drawable.draw(Canvas(it))
                            }
                        }
                        ownedBitmap = bitmap
                        CoverLoadResult(CoverImage.Static(bitmap), false)
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    coroutineContext.ensureActive()
                    CoverLoadResult(CoverImage.Static(configuration.defaultBitmap), true)
                }
            }
            coroutineContext.ensureActive()
            delivered = true
            return image
        } finally {
            if (animation == null || !delivered) {
                animation?.release() ?: withContext(NonCancellable + Dispatchers.Main.immediate) { target?.let { Glide.with(context).clear(it) } }
            }
            if (!delivered) ownedBitmap?.recycle()
        }
    }
}
