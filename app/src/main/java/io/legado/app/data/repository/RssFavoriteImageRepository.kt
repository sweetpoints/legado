package io.legado.app.data.repository

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestOptions
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import kotlinx.coroutines.*

interface RssFavoriteImageRepository { suspend fun load(source: String, origin: String, width: Int, height: Int): AnimatedDrawableResource? }
class GlideRssFavoriteImageRepository(context: Context) : RssFavoriteImageRepository {
    private val context = context.applicationContext
    override suspend fun load(source: String, origin: String, width: Int, height: Int): AnimatedDrawableResource? {
        var target: FutureTarget<Drawable>? = null; var delivered = false
        try {
            val drawable = withContext(Dispatchers.IO) {
                withContext(Dispatchers.Main.immediate) {
                    target = ImageLoader.load(context, source)
                        .apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                        .centerCrop().submit(width.coerceAtLeast(1), height.coerceAtLeast(1))
                }
                runInterruptible { checkNotNull(target).get() }
            }
            currentCoroutineContext().ensureActive()
            val request = checkNotNull(target); delivered = true
            return AnimatedDrawableResource(drawable) { Glide.with(context).clear(request) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return null }
        finally { if (!delivered) withContext(NonCancellable + Dispatchers.Main.immediate) { target?.let { Glide.with(context).clear(it) } } }
    }
}
