package io.legado.app.ui.main.explore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreHomeSessionTest {
    @Test
    fun twoStoresSerializeAtomicWritesAndRecoveredOwnerRejectsLateWritesAndCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val holdNextWrite = AtomicBoolean(false)
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val restoreEntered = CountDownLatch(1)
        val oldOwner =
            FileExploreHomeSessionStorage(context, token) {
                if (holdNextWrite.compareAndSet(true, false)) {
                    writeEntered.countDown()
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                }
            }
        val recoveredOwner = FileExploreHomeSessionStorage(context, token)
        val workers = Executors.newFixedThreadPool(2)
        try {
            assertTrue(oldOwner.write(ExploreHomeSession(revision = 1, query = "initial")))
            holdNextWrite.set(true)
            val acceptedWrite =
                workers.submit<Boolean> {
                    oldOwner.write(ExploreHomeSession(revision = 2, query = "accepted"))
                }
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
            val restored =
                workers.submit<ExploreHomeSession> {
                    restoreEntered.countDown()
                    recoveredOwner.read()
                }
            assertTrue(restoreEntered.await(5, TimeUnit.SECONDS))
            assertFalse("Restore must wait for the same UUID's Atomic write", restored.isDone)
            releaseWrite.countDown()
            assertTrue(acceptedWrite.get(5, TimeUnit.SECONDS))
            assertEquals("accepted", restored.get(5, TimeUnit.SECONDS).query)
            assertTrue(recoveredOwner.write(ExploreHomeSession(revision = 3, query = "recovered")))
            assertFalse(oldOwner.write(ExploreHomeSession(revision = 4, query = "late old owner")))
            oldOwner.delete()
            assertEquals("recovered", recoveredOwner.read().query)
            assertFalse(
                recoveredOwner.write(ExploreHomeSession(revision = 3, query = "same revision"))
            )
            recoveredOwner.delete()
            assertFalse(oldOwner.write(ExploreHomeSession(revision = 5, query = "resurrection")))
        } finally {
            releaseWrite.countDown()
            workers.shutdownNow()
            oldOwner.delete()
            recoveredOwner.delete()
        }
    }

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
