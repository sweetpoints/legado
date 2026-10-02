package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class TextDialogRequestRepositoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation(); private val context = instrumentation.targetContext
    private val ids = mutableListOf<String>()
    @After fun cleanup() { ids.forEach { id -> listOf("$id.json", "$id.json.bak").forEach { File(context.filesDir, "text-dialog-requests/$it").delete() } } }
    @Test fun pendingBackgroundStageLoadsLargeContentAndNewRepositoryRestoresAllConstructorOptions() = runBlocking {
        val request = TextDialogRequest("Help", "Large中文 ".repeat(100000), "MD", 5000, true, true)
        lateinit var id: String
        instrumentation.runOnMainSync { id = FileTextDialogRequestRepository.stage(context, request); ids += id }
        assertEquals(request, FileTextDialogRequestRepository(context).load(id))
        assertEquals(request, FileTextDialogRequestRepository(context).load(id))
        assertTrue(id.length < 100); assertTrue(File(context.filesDir, "text-dialog-requests/$id.json").isFile)
    }
    @Test fun unknownOrInvalidRequestsReportErrorsInsteadOfSilentlyReturningEmptyContent() = runBlocking {
        assertTrue(runCatching { FileTextDialogRequestRepository(context).load("not-staged") }.isFailure)
        assertTrue(runCatching { FileTextDialogRequestRepository(context).load("../escape") }.isFailure)
    }
}
