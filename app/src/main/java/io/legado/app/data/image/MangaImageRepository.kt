package io.legado.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.request.FutureTarget
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import io.legado.app.help.glide.progress.OnProgressListener
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.model.BookCover
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** The request captures book identity instead of consulting the active reader after suspension. */
data class MangaImageRequest(
    val bookUrl: String,
    val sourceOrigin: String?,
    val imageUrl: String,
    val transformation: Transformation<Bitmap>? = null,
)

interface MangaImageRepository {
    suspend fun load(
        request: MangaImageRequest,
        onProgress: (Int) -> Unit = {},
    ): AnimatedDrawableResource

    suspend fun preload(request: MangaImageRequest)
}

/** Keeps BookCover's manga proxy/header/cache pipeline and leases even static pooled drawables. */
class GlideMangaImageRepository(context: Context) : MangaImageRepository {
    private val context = context.applicationContext

    private suspend fun path(request: MangaImageRequest): String =
        withContext(Dispatchers.IO) {
            appDb.bookDao.getBook(request.bookUrl)?.let { book ->
                BookHelp.getImage(book, request.imageUrl).takeIf { it.isFile }?.absolutePath
            } ?: request.imageUrl
        }

    override suspend fun load(
        request: MangaImageRequest,
        onProgress: (Int) -> Unit,
    ): AnimatedDrawableResource {
        var target: FutureTarget<Drawable>? = null
        var resource: AnimatedDrawableResource? = null
        var delivered = false
        val listener: OnProgressListener = { _, percentage, _, _ -> onProgress(percentage) }
        ProgressManager.addListener(request.imageUrl, listener)
        try {
            val localPath = path(request)
            withContext(Dispatchers.Main.immediate) {
                target =
                    BookCover.loadManga(
                            context,
                            localPath,
                            sourceOrigin = request.sourceOrigin,
                            transformation = request.transformation,
                        )
                        .submit()
            }
            val requestTarget = checkNotNull(target)
            val drawable = withContext(Dispatchers.IO) { runInterruptible { requestTarget.get() } }
            // Compose draws the original pooled drawable, so its request stays alive until
            // disposal.
            resource =
                AnimatedDrawableResource(drawable) { Glide.with(context).clear(requestTarget) }
            coroutineContext.ensureActive()
            delivered = true
            return resource
        } finally {
            // A replaced page may already own a listener for the same URL. Never detach its work.
            ProgressManager.removeListener(request.imageUrl, listener)
            if (!delivered) {
                resource?.release()
                    ?: withContext(NonCancellable + Dispatchers.Main.immediate) {
                        target?.let { Glide.with(context).clear(it) }
                    }
            }
        }
    }

    override suspend fun preload(request: MangaImageRequest) {
        var target: FutureTarget<java.io.File>? = null
        try {
            val localPath = path(request)
            withContext(Dispatchers.Main.immediate) {
                target =
                    BookCover.preloadManga(
                            context,
                            localPath,
                            sourceOrigin = request.sourceOrigin,
                        )
                        .submit()
            }
            withContext(Dispatchers.IO) { runInterruptible { checkNotNull(target).get() } }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                target?.let { Glide.with(context).clear(it) }
            }
        }
    }
}
