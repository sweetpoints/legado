package io.legado.app.ui.association

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.FutureTarget
import io.legado.app.help.glide.ImageLoader
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal data class VerificationCodeImage(val bitmap: Bitmap, val previewSrc: String)

/** Each delivered preview is an immutable file, independent of shared recycled bitmap caches. */
internal class VerificationCodeImageLoader(context: Context) {
    private val context = context.applicationContext

    suspend fun load(url: String, sourceOrigin: String?): VerificationCodeImage {
        var target: FutureTarget<Bitmap>? = null
        var displayBitmap: Bitmap? = null
        var previewFile: File? = null
        var targetCleared = false
        var delivered = false
        try {
            withContext(Dispatchers.Main.immediate) {
                target =
                    ImageLoader.loadBitmap(context, url, sourceOrigin)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .submit()
            }
            val requestTarget = checkNotNull(target)
            val loaded =
                withContext(Dispatchers.IO) {
                    val resource = runInterruptible { requestTarget.get() }
                    val config =
                        resource.config?.takeUnless { it == Bitmap.Config.HARDWARE }
                            ?: Bitmap.Config.ARGB_8888
                    val display = checkNotNull(resource.copy(config, false))
                    displayBitmap = display
                    coroutineContext.ensureActive()
                    val directory = File(context.cacheDir, "verification-previews")
                    check(directory.isDirectory || directory.mkdirs()) {
                        "Cannot create verification preview directory"
                    }
                    val file = File.createTempFile("verification-", ".png", directory)
                    previewFile = file
                    file.outputStream().use { output ->
                        check(display.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "Cannot write verification preview"
                        }
                    }
                    coroutineContext.ensureActive()
                    // Delivered files stay available to an already open/restored PhotoDialog.
                    VerificationCodeImage(display, file.absolutePath)
                }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                Glide.with(context).clear(requestTarget)
            }
            targetCleared = true
            coroutineContext.ensureActive()
            delivered = true
            return loaded
        } finally {
            if (!targetCleared)
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    target?.let { Glide.with(context).clear(it) }
                }
            if (!delivered)
                withContext(NonCancellable + Dispatchers.IO) {
                    previewFile?.delete()
                    displayBitmap?.recycle()
                }
        }
    }
}
