package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.utils.QRCodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RssSourceEditorShareRepository(context: Context) {
    private val context = context.applicationContext
    suspend fun qr(text: String): String = withContext(Dispatchers.IO) {
        val bitmap = QRCodeUtils.createQRCode(text, errorCorrectionLevel = ErrorCorrectionLevel.L)
            ?: error(context.getString(io.legado.app.R.string.text_too_long_qr_error))
        val file = File(context.externalCacheDir ?: context.cacheDir, "rss-source-qr.png")
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; file.absolutePath }
        catch (failure: Throwable) { file.delete(); throw failure }
        finally { bitmap.recycle() }
    }
}
