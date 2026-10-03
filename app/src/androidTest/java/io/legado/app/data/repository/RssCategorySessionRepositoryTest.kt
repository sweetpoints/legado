package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RssCategorySessionRepositoryTest {
    private val directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "rss-category-fixture-${UUID.randomUUID()}")
    private val session = UUID.randomUUID().toString()
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun largeRequestAndExactDraftRestoreAcrossFreshStoreInstance() = runBlocking {
        val value = RssCategorySession(RssCategoryRequest("S".repeat(500000), "U".repeat(500000), ""), "查询".repeat(500000), 8)
        val first = FileRssCategorySessionRepository(directory); first.write(session, value)
        assertEquals(value, FileRssCategorySessionRepository(directory).read(session))
    }
    @Test fun olderWriteCannotOverwriteNewerCheckpoint() = runBlocking {
        val store = FileRssCategorySessionRepository(directory)
        val latest = RssCategorySession(RssCategoryRequest("source"), "Latest", 30)
        store.write(session, latest); store.write(session, latest.copy(draft = "Stale", revision = 2))
        assertEquals(latest, store.read(session))
    }
    @Test fun releasedOwnerFencesLateWritesAndDeletesOwnedLargePayload() = runBlocking {
        val store = FileRssCategorySessionRepository(directory); val value = RssCategorySession(RssCategoryRequest("source"), "Draft", 1)
        store.write(session, value)
        File(directory,"$session.json.bak").writeText("private backup");File(directory,"$session.json.new").writeText("private pending")
        store.release(session); store.write(session, value.copy(revision = 99))
        assertNull(FileRssCategorySessionRepository(directory).read(session)); assertFalse(File(directory, "$session.json").exists())
        assertTrue(File(directory, "$session.released").exists())
        listOf(".json",".json.bak",".json.new").forEach { assertFalse(File(directory,session+it).exists()) }
        assertTrue(File(directory,"$session.released").renameTo(File(directory,"$session.released.bak")))
        store.write(session,value.copy(revision=100));assertNull(store.read(session))
    }
    @Test fun failedFirstWriteCanRetrySameIdAfterDirectoryRepair() = runBlocking {
        directory.writeText("block directory")
        val store = FileRssCategorySessionRepository(directory); val value = RssCategorySession(RssCategoryRequest("source"), "Exact draft", 1)
        assertTrue(runCatching { store.write(session, value) }.isFailure)
        assertTrue(directory.delete()); store.write(session, value)
        assertEquals(value, store.read(session))
    }
}
