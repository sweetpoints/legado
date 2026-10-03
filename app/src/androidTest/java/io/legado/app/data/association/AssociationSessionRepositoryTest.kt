package io.legado.app.data.association

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationSessionRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun largeLaunchAndBinaryPayloadRestorePrivatelyAndRejectEqualRevision() = runBlocking {
        val directory = File(context.cacheDir, "association-session-${UUID.randomUUID()}")
        val repository = FileAssociationSessionRepository(context, directory)
        val input =
            AssociationInput(
                AssociationHostKind.Online,
                AssociationInputKind.View,
                uris = listOf("legado://import/readConfig?src=" + "url".repeat(100_000)),
                text = "json".repeat(100_000),
            )
        val ticket = repository.create(input)
        try {
            val restored = FileAssociationSessionRepository(context, directory).read(ticket)
            assertEquals(input, restored.input)
            assertFalse(repository.write(ticket, restored.copy(phase = AssociationPhase.Finished)))
            assertTrue(
                repository.write(
                    ticket,
                    restored.copy(revision = 1, phase = AssociationPhase.ReadConfig),
                )
            )
            assertFalse(
                FileAssociationSessionRepository(context, directory)
                    .write(ticket, restored.copy(revision = 1, error = "stale"))
            )
            val bytes = ByteArray(300_000) { (it % 256).toByte() }
            repository.writeBytes(ticket, "config.data", bytes)
            assertTrue(
                FileAssociationSessionRepository(context, directory)
                    .readBytes(ticket, "config.data")
                    .contentEquals(bytes)
            )
            assertTrue(
                runCatching { repository.writeBytes(ticket, "config.data", byteArrayOf(1)) }
                    .isFailure
            )
            assertTrue(
                runCatching { repository.writeBytes(ticket, "../neighbor", bytes) }.isFailure
            )
            assertEquals(AssociationPhase.ReadConfig, repository.read(ticket).phase)
        } finally {
            repository.release(ticket)
            directory.deleteRecursively()
        }
    }

    @Test
    fun closedBackupFencesLateOwnerAndOnlyDeletesItsOwnPayloads() = runBlocking {
        val directory = File(context.cacheDir, "association-session-${UUID.randomUUID()}")
        val first = FileAssociationSessionRepository(context, directory)
        val second = FileAssociationSessionRepository(context, directory)
        val input =
            AssociationInput(
                AssociationHostKind.File,
                AssociationInputKind.SharedText,
                text = "payload",
            )
        val ticket = first.create(input)
        val neighbor = first.create(input)
        try {
            first.writeBytes(ticket, "accepted.data", byteArrayOf(1, 2, 3))
            val snapshot = first.read(ticket)
            first.release(ticket)
            assertFalse(File(directory, ticket).exists())
            val marker = File(directory, "$ticket.closed")
            assertTrue(marker.renameTo(File(directory, "$ticket.closed.bak")))
            assertTrue(
                runCatching { second.write(ticket, snapshot.copy(revision = 2)) }.exceptionOrNull()
                    is AssociationSessionClosed
            )
            assertTrue(
                runCatching { second.writeBytes(ticket, "late.data", byteArrayOf(4)) }
                    .exceptionOrNull() is AssociationSessionClosed
            )
            assertTrue(
                runCatching { second.read(ticket) }.exceptionOrNull() is AssociationSessionClosed
            )
            assertEquals(input, second.read(neighbor).input)
            second.release(ticket)
            assertFalse(File(directory, ticket).exists())
        } finally {
            first.release(ticket)
            first.release(neighbor)
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancelledAcceptedCreationReleasesOnlyItsNeverDeliveredAllocation() = runBlocking {
        val directory = File(context.cacheDir, "association-session-${UUID.randomUUID()}")
        val repository = FileAssociationSessionRepository(context, directory)
        val neighbor =
            repository.create(AssociationInput(AssociationHostKind.File, AssociationInputKind.View))
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val controlled =
            FileAssociationSessionRepository(
                context,
                directory,
                beforeWrite = { value ->
                    if (value.input.text == "cancel-after-acceptance") {
                        entered.countDown()
                        check(unblock.await(5, TimeUnit.SECONDS))
                    }
                },
            )
        val attempt =
            launch(Dispatchers.Default) {
                controlled.create(
                    AssociationInput(
                        AssociationHostKind.File,
                        AssociationInputKind.SharedText,
                        text = "cancel-after-acceptance",
                    )
                )
            }
        try {
            assertTrue(withContext(Dispatchers.Default) { entered.await(5, TimeUnit.SECONDS) })
            attempt.cancel()
            unblock.countDown()
            attempt.join()
            assertTrue(attempt.isCancelled)
            assertEquals(
                listOf(neighbor),
                directory.listFiles()!!.filter { it.isDirectory }.map { it.name },
            )
            assertEquals(1, directory.listFiles()!!.count { it.name.endsWith(".closed") })
            assertEquals(AssociationInputKind.View, repository.read(neighbor).input.kind)
        } finally {
            unblock.countDown()
            attempt.cancelAndJoin()
            repository.release(neighbor)
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptRestorationAndFailedWriteNeverReplaceExistingDraft() = runBlocking {
        val directory = File(context.cacheDir, "association-session-${UUID.randomUUID()}")
        val repository = FileAssociationSessionRepository(context, directory)
        val input =
            AssociationInput(
                AssociationHostKind.File,
                AssociationInputKind.SharedText,
                text = "original",
            )
        val ticket = repository.create(input)
        try {
            val original = repository.read(ticket)
            val failing =
                FileAssociationSessionRepository(
                    context,
                    directory,
                    beforeWrite = { error("controlled disk failure") },
                )
            assertTrue(
                runCatching {
                    failing.write(
                        ticket,
                        original.copy(revision = 1, input = input.copy(text = "replacement")),
                    )
                }
                    .isFailure
            )
            assertEquals(original, repository.read(ticket))
            val body = File(directory, "$ticket/session.json")
            body.writeText("not JSON")
            assertTrue(runCatching { repository.read(ticket) }.isFailure)
            assertTrue(
                runCatching { repository.write(ticket, original.copy(revision = 2)) }.isFailure
            )
            assertEquals("not JSON", body.readText())
        } finally {
            repository.release(ticket)
            directory.deleteRecursively()
        }
    }
}
