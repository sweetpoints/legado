package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SimulatedReadingRequestRepositoryTest {
    @Test
    fun fullBookOwnerStaysPrivateAndReleaseRemovesAtomicSidecars() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "simulation-${UUID.randomUUID()}")
        try {
            val repository = FileSimulatedReadingRequestRepository(context, directory)
            val request =
                SimulatedReadingRequest(
                    "full".repeat(250000),
                    SimulatedReadingSettings(false, "2026-10-03", "8", "3", 15),
                )
            val ticket = repository.create(request)
            assertEquals(request, repository.read(ticket))
            File(directory, "$ticket.json.bak").writeText("private")
            File(directory, "$ticket.json.new").writeText("private")
            repository.release(ticket)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(runCatching { repository.read(ticket) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }
}
