package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.help.glide.ImageLoader
import kotlinx.coroutines.*

interface MarkdownImageRepository { suspend fun load(source: String, width: Int): AnimatedDrawableResource? }
/** Retains Glide's drawable lease for static and animated images until Compose stops drawing. */
class GlideMarkdownImageRepository(context: Context) : MarkdownImageRepository {
    private val context = context.applicationContext
    override suspend fun load(source: String, width: Int): AnimatedDrawableResource? {
        var target: FutureTarget<Drawable>? = null; var delivered = false
        try {
            val drawable = withContext(Dispatchers.IO) {
                withContext(Dispatchers.Main.immediate) {
                    val request = if (source.startsWith("file:", true) || source.startsWith("android.resource:", true)) Glide.with(context).load(Uri.parse(source)) else ImageLoader.load(context, source)
                    target = request.fitCenter().submit(width.coerceAtLeast(1), width.coerceAtLeast(1) * 4)
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
