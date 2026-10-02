package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.ui.code.CodeTextTransfer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class CodeDialogTransferRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun largeTransferIsCompatibleWithNativeEditorAndFreshRepository() = runBlocking {
        val repo = FileCodeDialogTransferRepository(context); val body = "中文\n".repeat(200000)
        val path = repo.write(body)
        try { assertEquals(body, CodeTextTransfer.read(context, path)); assertEquals(body, FileCodeDialogTransferRepository(context).read(path)) }
        finally { repo.delete(path, path) }
        assertFalse(File(path).exists())
    }
    @Test fun cancelledInputAndReturnedNativeOutputAreBothReleased() = runBlocking {
        val repo = FileCodeDialogTransferRepository(context); val input = repo.write("original")
        val output = CodeTextTransfer.write(context, "edited")
        assertEquals("edited", repo.read(output)); repo.delete(input, output)
        assertFalse(File(input).exists()); assertFalse(File(output).exists())
    }
    @Test fun invalidPathCannotReadOrDeleteOtherApplicationFiles() = runBlocking {
        val file = File(context.filesDir, "code-text-${UUID.randomUUID()}.txt"); file.writeText("private")
        try {
            val repo = FileCodeDialogTransferRepository(context)
            assertTrue(runCatching { repo.read(file.absolutePath) }.isFailure); repo.delete(file.absolutePath)
            assertEquals("private", file.readText())
        } finally { file.delete() }
    }
}
