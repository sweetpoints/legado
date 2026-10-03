package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.AtomicFile
import androidx.core.net.toUri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.*
import io.legado.app.model.backup.*
import io.legado.app.utils.QRCodeUtils
import java.io.File
import java.util.UUID

internal class AppBackupLanStore(context: Context) : BackupLanStore {
    private val application = context.applicationContext
    override suspend fun prepare(): BackupLanPrepared {
        val backup = Backup.backupForLanTransferLocked(application)
        val device = AppConfig.webDavDeviceName?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val session = LanBackupTransfer.prepare(backup, device)
        val file = File(application.cacheDir, "backup-settings-lan/${UUID.randomUUID()}.png")
        try {
            val bitmap = QRCodeUtils.createQRCode(session.qrText) ?: throw NoStackTraceException("生成二维码失败")
            try {
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val atomic = AtomicFile(file); val output = atomic.startWrite()
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); atomic.finishWrite(output) }
                catch (error: Throwable) { atomic.failWrite(output); throw error }
            } finally { bitmap.recycle() }
            val preparedOffer = BackupLanOffer(UUID.randomUUID().toString(), file.path, session.descriptor.expiresAt)
            return object : BackupLanPrepared {
                override val offer = preparedOffer
                override fun close() { try { session.close() } finally { AtomicFile(file).delete() } }
            }
        } catch (error: Throwable) { try { session.close() } finally { AtomicFile(file).delete() }; throw error }
    }
    override suspend fun decode(qrText: String): BackupLanReceiveInfo {
        val descriptor = LanBackupTransfer.decodeDescriptor(qrText).getOrThrow()
        return BackupLanReceiveInfo(descriptor.size, descriptor.deviceName.ifBlank { descriptor.hosts.first() })
    }
    override suspend fun receive(qrText: String): BackupLanReceived = Received(LanBackupTransfer.receive(application, qrText))
    override suspend fun backupBeforeRestore() { Backup.backupBeforeLanRestoreLocked(application) }
    override suspend fun requireSpace(bytes: Long) { LanBackupTransfer.requireRestoreSpace(application, bytes) }
    override suspend fun restore(received: BackupLanReceived) { val actual = (received as Received).value
        Restore.restoreOrThrow(application, actual.file.toUri(), lanTransfer = true)
    }
    private class Received(val value: ReceivedLanBackup) : BackupLanReceived {
        override val uncompressedBytes = value.uncompressedBytes
        override fun close() { value.file.parentFile?.deleteRecursively() }
    }
}
