package io.legado.app.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.inputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class QrScanSession(
    val id: String,
    val revision: Long = 1,
    val gallery: String? = null,
    val resultReady: Boolean = false,
    val result: String? = null,
    val completed: Boolean = false,
)

interface QrScanRepository {
    suspend fun open(id: String?): QrScanSession

    suspend fun write(value: QrScanSession)

    suspend fun decode(uri: String): String?

    suspend fun release(id: String)
}

/** Gallery bitmaps and large scan results are owned here, outside Activity and SavedState. */
class FileQrScanRepository(
    context: Context,
    private val directory: File = File(context.filesDir, "compose-qr-scan"),
) : QrScanRepository {
    private val context = context.applicationContext

    private fun file(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        return AtomicFile(File(directory, "$id.json"))
    }

    private fun gate(id: String) =
        gates[(file(id).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun read(id: String) =
        file(id).openRead().bufferedReader().use {
            GSON.fromJsonObject<QrScanSession>(it.readText()).getOrThrow().also { value ->
                check(value.id == id)
            }
        }

    private fun put(value: QrScanSession) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = file(value.id)
        val stream = atomic.startWrite()
        try {
            stream.write(GSON.toJson(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    override suspend fun open(id: String?): QrScanSession =
        withContext(Dispatchers.IO) {
            if (id != null) gate(id).withLock { read(id) }
            else
                QrScanSession(UUID.randomUUID().toString()).also {
                    gate(it.id).withLock { put(it) }
                }
        }

    override suspend fun write(value: QrScanSession) =
        withContext(Dispatchers.IO) {
            gate(value.id).withLock {
                val current = read(value.id)
                if (value.revision > current.revision) put(value)
            }
        }

    override suspend fun decode(uri: String): String? =
        withContext(Dispatchers.IO) {
            val bitmap =
                Uri.parse(uri).inputStream(context).getOrThrow().use {
                    BitmapFactory.decodeStream(it)
                } ?: error("无法读取二维码图片")
            try {
                currentCoroutineContext().ensureActive()
                QRCodeUtils.parseCodeResult(bitmap)?.text
            } finally {
                bitmap.recycle()
            }
        }

    override suspend fun release(id: String) =
        withContext(Dispatchers.IO) { gate(id).withLock { file(id).delete() } }

    companion object {
        private val gates = Array(64) { Mutex() }
    }
}
