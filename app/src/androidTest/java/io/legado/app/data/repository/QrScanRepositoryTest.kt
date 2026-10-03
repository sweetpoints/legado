package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.legado.app.utils.QRCodeUtils
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class QrScanRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "qr-scan-${UUID.randomUUID()}")
    @Before fun setup() { directory.mkdirs() }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun actualAtomicFileRestoresLargeResultAndNullResultDistinctionAndRejectsOldRevision() = runBlocking {
        val repo = FileQrScanRepository(context, directory); val initial = repo.open(null)
        val current = initial.copy(revision = 4, resultReady = true, result = "x".repeat(400000))
        repo.write(current); FileQrScanRepository(context, directory).write(initial.copy(revision = 2))
        assertEquals(current, repo.open(initial.id))
        val nullResult = current.copy(revision = 5, result = null); repo.write(nullResult)
        assertTrue(repo.open(initial.id).resultReady); assertNull(repo.open(initial.id).result)
    }
    @Test fun releaseDeletesOnlyOwnedSessionAndLateWritesCannotRecreateItsPayload() = runBlocking {
        val repo = FileQrScanRepository(context, directory); val one = repo.open(null); val other = repo.open(null)
        FileQrScanRepository(context, directory).release(one.id)
        assertTrue(runCatching { repo.write(one.copy(revision = 100)) }.isFailure)
        assertFalse(File(directory, "${one.id}.json").exists()); assertEquals(other, repo.open(other.id))
    }
    @Test fun actualGalleryDecodeReadsQrPngAndReturnsNullForReadableImageWithoutQr() = runBlocking {
        val repo = FileQrScanRepository(context, directory)
        val qr = File(directory, "qr.png"); val blank = File(directory, "blank.png")
        withContext(Dispatchers.IO) {
            checkNotNull(QRCodeUtils.createQRCode("Compose gallery fixture", 256)).apply {
                qr.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
            }
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE); blank.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
            }
        }
        assertEquals("Compose gallery fixture", repo.decode(qr.toURI().toString()))
        assertNull(repo.decode(blank.toURI().toString()))
        assertTrue(runCatching { repo.decode(File(directory, "missing.png").toURI().toString()) }.isFailure)
    }
}
