package io.legado.app.data.repository

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.help.CacheManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import kotlinx.coroutines.*

interface RssArticleImageRepository {
    suspend fun ratio(source: String): Float?

    suspend fun load(
        source: String,
        origin: String,
        width: Int,
        height: Int,
        natural: Boolean,
    ): AnimatedDrawableResource?
}

interface RssArticleRatioStore {
    fun get(source: String): Float?

    fun put(source: String, ratio: Float)
}

/**
 * Retains the waterfall's original URL cache key, 399 entries and twenty-day persisted lifetime.
 */
class CacheRssArticleRatioStore : RssArticleRatioStore {
    override fun get(source: String): Float? =
        cache[source]
            ?: CacheManager.getFloat("img_ar_$source")
                ?.takeIf { it.isFinite() && it > 0 }
                ?.also { cache.put(source, it) }

    override fun put(source: String, ratio: Float) {
        if (!ratio.isFinite() || ratio <= 0) return
        cache.put(source, ratio)
        CacheManager.put("img_ar_$source", ratio, 60 * 60 * 24 * 20)
    }

    private companion object {
        val cache = LruCache<String, Float>(399)
    }
}

internal fun rssArticleImageRatio(width: Int, height: Int): Float? =
    if (width > 0 && height > 0) (height.toFloat() / width).takeIf { it.isFinite() && it > 0 }
    else null

class GlideRssArticleImageRepository(
    context: Context,
    private val ratios: RssArticleRatioStore = CacheRssArticleRatioStore(),
) : RssArticleImageRepository {
    private val context = context.applicationContext

    override suspend fun ratio(source: String): Float? =
        withContext(Dispatchers.IO) { ratios.get(source) }

    override suspend fun load(
        source: String,
        origin: String,
        width: Int,
        height: Int,
        natural: Boolean,
    ): AnimatedDrawableResource? {
        var target: FutureTarget<Drawable>? = null
        var delivered = false
        try {
            val drawable =
                withContext(Dispatchers.IO) {
                    withContext(Dispatchers.Main.immediate) {
                        val request =
                            ImageLoader.load(context, source)
                                .apply(
                                    RequestOptions()
                                        .set(OkHttpModelLoader.sourceOriginOption, origin)
                                )
                        target =
                            if (natural)
                                request
                                    .dontTransform()
                                    .submit(width.coerceAtLeast(1), Target.SIZE_ORIGINAL)
                            else
                                request
                                    .centerCrop()
                                    .submit(width.coerceAtLeast(1), height.coerceAtLeast(1))
                    }
                    runInterruptible { checkNotNull(target).get() }
                        .also { resource ->
                            if (natural)
                                rssArticleImageRatio(
                                        resource.intrinsicWidth,
                                        resource.intrinsicHeight,
                                    )
                                    ?.let { ratios.put(source, it) }
                        }
                }
            currentCoroutineContext().ensureActive()
            val request = checkNotNull(target)
            delivered = true
            return AnimatedDrawableResource(drawable) { Glide.with(context).clear(request) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return null
        } finally {
            if (!delivered)
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    target?.let { Glide.with(context).clear(it) }
                }
        }
    }
}
