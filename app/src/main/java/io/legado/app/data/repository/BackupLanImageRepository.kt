package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.*

internal interface BackupLanImageRepository {
    suspend fun load(path: String): Bitmap
}

/** Only the privately generated LAN image is readable; decoding never runs on Main. */
internal class FileBackupLanImageRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupLanImageRepository {
    private val application = context.applicationContext

    override suspend fun load(path: String): Bitmap {
        var owned: Bitmap? = null
        try {
            return withContext(io) {
                    val directory = File(application.cacheDir, "backup-settings-lan").canonicalFile
                    val file = File(path).canonicalFile
                    require(file.parentFile == directory) { "Invalid backup QR image" }
                    val bitmap =
                        BitmapFactory.decodeFile(file.path)
                            ?: error("Unable to read backup QR image")
                    owned = bitmap
                    currentCoroutineContext().ensureActive()
                    bitmap
                }
                .also { owned = null }
        } catch (error: Throwable) {
            owned?.recycle()
            throw error
        }
    }
}
