package io.legado.app.data.association

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationNativeResultRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun resultBeforeVmLoadRestoresLargeDirectoryOnceAndRejectsDifferentOwner() = runBlocking {
        val directory = File(context.cacheDir, "association-native-${UUID.randomUUID()}")
        val sessions = FileAssociationSessionRepository(context, directory)
        val ticket =
            sessions.create(AssociationInput(AssociationHostKind.File, AssociationInputKind.View))
        val receipt = AssociationNativeReceipt("picker", 0, AssociationNativeKind.SelectDirectory)
        try {
            val original = sessions.read(ticket)
            assertTrue(
                sessions.write(
                    ticket,
                    original.copy(revision = 1, claimedEffects = listOf(receipt)),
                )
            )
            val result =
                AssociationNativeResult(
                    receipt,
                    directory = "content://provider/" + "long".repeat(100_000),
                )
            val results = FileAssociationNativeResultRepository(sessions)
            assertTrue(results.record(ticket, result))
            assertFalse(results.record(ticket, result.copy(directory = null)))
            assertFalse(results.record(ticket, result.copy(receipt = receipt.copy(generation = 1))))
            assertFalse(results.record(ticket, result.copy(receipt = receipt.copy(token = "old"))))
            val restored =
                FileAssociationNativeResultRepository(
                    FileAssociationSessionRepository(context, directory)
                )
            assertEquals(listOf(result), restored.read(ticket))
            restored.acknowledge(ticket, receipt.copy(generation = 1))
            assertEquals(listOf(result), results.read(ticket))
            restored.acknowledge(ticket, receipt)
            assertTrue(results.read(ticket).isEmpty())
        } finally {
            sessions.release(ticket)
            directory.deleteRecursively()
        }
    }

    @Test
    fun releasedOwnerCannotAcceptLatePermissionOrRecreatePayload() = runBlocking {
        val directory = File(context.cacheDir, "association-native-${UUID.randomUUID()}")
        val sessions = FileAssociationSessionRepository(context, directory)
        val ticket =
            sessions.create(AssociationInput(AssociationHostKind.File, AssociationInputKind.View))
        val receipt =
            AssociationNativeReceipt("permission", 0, AssociationNativeKind.StoragePermission)
        try {
            assertTrue(
                sessions.write(
                    ticket,
                    sessions.read(ticket).copy(revision = 1, claimedEffects = listOf(receipt)),
                )
            )
            val results = FileAssociationNativeResultRepository(sessions)
            assertTrue(
                results.record(ticket, AssociationNativeResult(receipt, permissionGranted = false))
            )
            sessions.release(ticket)
            assertTrue(
                runCatching {
                    results.record(
                        ticket,
                        AssociationNativeResult(receipt, permissionGranted = true),
                    )
                }
                    .exceptionOrNull() is AssociationSessionClosed
            )
            assertFalse(File(directory, ticket).exists())
        } finally {
            sessions.release(ticket)
            directory.deleteRecursively()
        }
    }
}
