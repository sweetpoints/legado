package io.legado.app.ui.main.explore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreHomeSessionTest {
    @Test
    fun backupOnlySessionRestoresFullScriptDraftAndCleanupPreservesNeighbor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val neighborToken = UUID.randomUUID().toString()
        val storage = FileExploreHomeSessionStorage(context, token)
        val neighbor = FileExploreHomeSessionStorage(context, neighborToken)
        val directory = File(context.filesDir, "explore-home-sessions")
        val base = File(directory, "$token.json")
        val backup = File(directory, "$token.json.bak")
        val fullText = "full discovery draft".repeat(20_000)
        val effect =
            ExploreHomeEffect(
                "request",
                "script",
                "source",
                "button",
                fullText,
                mapOf("draft" to fullText),
            )
        val session =
            ExploreHomeSession(
                query = fullText,
                expandedUrl = "source",
                values = mapOf("source" to mapOf("draft" to fullText)),
                effect = effect,
            )
        try {
            storage.write(session)
            neighbor.write(ExploreHomeSession(query = "neighbor"))
            assertTrue(base.renameTo(backup))
            assertFalse(base.exists())
            assertEquals(session, storage.read())
            storage.delete()
            assertFalse(base.exists())
            assertFalse(backup.exists())
            assertEquals("neighbor", neighbor.read().query)
        } finally {
            storage.delete()
            neighbor.delete()
        }
    }
}
