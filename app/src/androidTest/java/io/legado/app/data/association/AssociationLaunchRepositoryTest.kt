package io.legado.app.data.association

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationLaunchRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun sendStreamTakesPrecedenceOverTextAndPreservesUriGrantFlags() {
        val uri = Uri.parse("content://provider/book")
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "ignored online URL")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val input = associationLaunchInput(intent, AssociationHostKind.File)
        assertEquals(AssociationInputKind.SharedUri, input.kind)
        assertEquals(listOf(uri.toString()), input.uris)
        assertEquals(null, input.text)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, input.intentFlags)
        intent.type = "application/javascript"
        assertEquals(
            AssociationInputKind.Invalid,
            associationLaunchInput(intent, AssociationHostKind.File).kind,
        )
    }

    @Test
    fun multipleIgnoresMimeFilterAndClipDataWhileViewAndOnlineKeepOriginalUri() {
        val uri = Uri.parse("content://provider/archive")
        val multiple =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/javascript"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
            }
        val input = associationLaunchInput(multiple, AssociationHostKind.File)
        assertEquals(AssociationInputKind.SharedUris, input.kind)
        assertEquals(listOf(uri.toString()), input.uris)
        val clipOnly =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                clipData = ClipData.newRawUri("ignored clip", uri)
            }
        assertTrue(associationLaunchInput(clipOnly, AssociationHostKind.File).uris.isEmpty())
        val data =
            Uri.parse("yuedu://import/readConfig?src=https%3A%2F%2Ffixture.test%2Fconfig.zip")
        val view = Intent(Intent.ACTION_VIEW, data)
        assertEquals(
            listOf(data.toString()),
            associationLaunchInput(view, AssociationHostKind.Online).uris,
        )
        assertEquals(
            listOf(data.toString()),
            associationLaunchInput(view, AssociationHostKind.File).uris,
        )
        assertEquals(
            emptyList<String>(),
            associationLaunchInput(Intent(Intent.ACTION_VIEW), AssociationHostKind.File).uris,
        )
    }

    @Test
    fun canceledOuterIoReturnReleasesUuidThatNeverReachedHost() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val returnGate = CompletableDeferred<Unit>()
        val released = AtomicReference<String?>()
        val ticket = UUID.randomUUID().toString()
        val sessions =
            object : AssociationSessionRepository {
                override suspend fun create(input: AssociationInput): String =
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        returnGate.await()
                        ticket
                    }

                override suspend fun release(ticket: String) {
                    released.set(ticket)
                }

                override suspend fun read(ticket: String): AssociationSession = error("Unused")

                override suspend fun write(ticket: String, value: AssociationSession): Boolean =
                    error("Unused")

                override suspend fun writeBytes(
                    ticket: String,
                    name: String,
                    bytes: ByteArray,
                ): Unit = error("Unused")

                override suspend fun readBytes(ticket: String, name: String): ByteArray =
                    error("Unused")
            }
        val repository = AssociationLaunchRepository(context, sessions)
        var delivered = false
        val delivery =
            launch(Dispatchers.Main) {
                repository.prepare(
                    Intent(Intent.ACTION_VIEW, Uri.parse("content://fixture/book")),
                    AssociationHostKind.File,
                )
                delivered = true
            }
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
            delivery.cancel()
            returnGate.complete(Unit)
            delivery.join()
            assertEquals(ticket, released.get())
            assertEquals(false, delivered)
        } finally {
            returnGate.complete(Unit)
            delivery.cancelAndJoin()
        }
    }

    @Test
    fun largeShareIsPrivatelyPreparedAndInputIntentRemainsUnmodifiedUntilHostAcceptsUuid() =
        runBlocking {
            val directory = File(context.cacheDir, "association-launch-${UUID.randomUUID()}")
            val sessions = FileAssociationSessionRepository(context, directory)
            val repository = AssociationLaunchRepository(context, sessions)
            val text = "large JSON content".repeat(100_000)
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
            val ticket = repository.prepare(intent, AssociationHostKind.File)
            try {
                assertTrue(runCatching { UUID.fromString(ticket) }.isSuccess)
                assertEquals(text, sessions.read(ticket).input.text)
                assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
                assertEquals(AssociationInputKind.SharedText, sessions.read(ticket).input.kind)
            } finally {
                sessions.release(ticket)
                directory.deleteRecursively()
            }
        }
}
