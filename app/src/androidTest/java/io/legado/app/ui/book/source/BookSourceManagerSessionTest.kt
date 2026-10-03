package io.legado.app.ui.book.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.book.source.manage.SourceManagerSession
import io.legado.app.ui.book.source.manage.SourceManagerSessionStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookSourceManagerSessionTest {
    @Test
    fun actualAtomicFileBackupRestoresLargeSessionAndCleanupLeavesNeighborUntouched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val neighborToken = UUID.randomUUID().toString()
        val store = SourceManagerSessionStore(context, token)
        val neighbor = SourceManagerSessionStore(context, neighborToken)
        val directory = File(context.filesDir, "source-manager-sessions")
        val base = File(directory, "$token.json")
        val backup = File(directory, "$token.json.bak")
        val temporary = File(directory, "$token.json.new")
        val neighborBase = File(directory, "$neighborToken.json")
        val snapshot =
            SourceManagerSession(
                query = "large query".repeat(20_000),
                selected = (0..10_000).map { "source-$it" }.toSet(),
            )
        try {
            store.write(snapshot)
            neighbor.write(SourceManagerSession(query = "neighbor"))
            assertTrue(base.renameTo(backup))
            assertFalse(base.exists())
            assertEquals(snapshot, store.read())
            assertTrue(base.exists())
            temporary.writeText("incomplete next write")
            store.delete()
            assertFalse(base.exists())
            assertFalse(backup.exists())
            assertFalse(temporary.exists())
            assertTrue(neighborBase.exists())
            assertEquals("neighbor", neighbor.read().query)
        } finally {
            store.delete()
            neighbor.delete()
        }
    }
}
