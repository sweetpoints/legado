package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.model.Debug
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AutoTaskDebugRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File

    @Before
    fun before() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "debug-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        database.close()
        directory.deleteRecursively()
    }

    private fun repo() = AppAutoTaskDebugRepository(context, database, {}, directory)

    @Test
    fun immutableSnapshotRunsRealV8StreamsScopedLogsAndDoesNotPersistRuntime() = runBlocking {
        val repository = repo()
        val task =
            AutoTaskRule(
                "id",
                "Fixture",
                script = "java.log('fixture stream'); 42",
                lastRunAt = 90,
                lastLog = "old log",
                lastError = "old error",
                lastResult = "old result",
            )
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(task) }
        val snapshot = repository.load(task.id)!!
        withContext(Dispatchers.IO) {
            database.autoTaskRuleDao.upsert(task.copy(script = "99", lastLog = "fresh log"))
        }
        val logs = CopyOnWriteArrayList<String>()
        val lease = repository.acquire(snapshot) { logs += it }!!
        try {
            Debug.log("unrelated", "wrong scope")
            val result = lease.run()
            assertTrue(result.log.contains("[OK]"))
            assertTrue(result.log.contains("42"))
            assertTrue(logs.any { it.contains("fixture stream") })
            assertFalse(logs.any { it.contains("wrong scope") })
            withContext(Dispatchers.IO) {
                assertEquals(
                    task.copy(script = "99", lastLog = "fresh log"),
                    database.autoTaskRuleDao.getById(task.id),
                )
            }
        } finally {
            lease.close()
        }
        assertNull(Debug.callback)
    }

    @Test
    fun busyAcquireCannotReplaceOrCancelAnotherOwnerAndReleasedLeaseCannotReleaseSuccessor() =
        runBlocking {
            val repository = repo()
            val task = AutoTaskRule("id", "Fixture", script = "42")
            withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(task) }
            val snapshot = repository.load(task.id)!!
            val other =
                object : Debug.Callback {
                    override fun printLog(state: Int, msg: String) = Unit
                }
            assertTrue(Debug.startSimpleDebug(other, "other"))
            try {
                assertNull(repository.acquire(snapshot) {})
                assertSame(other, Debug.callback)
            } finally {
                Debug.cancelDebug(other)
            }
            val lease = repository.acquire(snapshot) {}!!
            lease.close()
            assertNull(Debug.callback)
            assertTrue(Debug.startSimpleDebug(other, "other"))
            try {
                lease.close()
                assertSame(other, Debug.callback)
            } finally {
                Debug.cancelDebug(other)
            }
        }

    @Test
    fun realRunnerCancellationInterruptsV8AndScopedLeaseCleanupLeavesRuntimeUntouched() =
        runBlocking {
            val repository = repo()
            val task =
                AutoTaskRule(
                    "id",
                    "Loop",
                    script = "while (true) {}",
                    lastRunAt = 90,
                    lastLog = "retained",
                )
            withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(task) }
            val snapshot = repository.load(task.id)!!
            val started = CompletableDeferred<Unit>()
            val lease =
                repository.acquire(snapshot) {
                    if (it.contains("Running")) started.complete(Unit)
                }!!
            val job = launch(Dispatchers.Default) { lease.run() }
            try {
                withTimeout(5000) {
                    started.await()
                    job.cancelAndJoin()
                }
                assertTrue(job.isCancelled)
                withContext(Dispatchers.IO) {
                    assertEquals(task, database.autoTaskRuleDao.getById(task.id))
                }
            } finally {
                job.cancel()
                lease.close()
            }
            assertNull(Debug.callback)
        }

    @Test
    fun realFailureLogContainsScriptErrorAndLeaseIsSingleUse() = runBlocking {
        val repository = repo()
        val task = AutoTaskRule("id", "Fail", script = "throw new Error('fixture failure')")
        withContext(Dispatchers.IO) { database.autoTaskRuleDao.upsert(task) }
        val lease = repository.acquire(repository.load(task.id)!!) {}!!
        try {
            val result = lease.run()
            assertTrue(result.log.contains("[ERROR]"))
            assertTrue(result.log.contains("fixture failure"))
            try {
                lease.run()
                fail("lease cannot execute twice")
            } catch (_: IllegalStateException) {}
        } finally {
            lease.close()
        }
        assertNull(Debug.callback)
    }

    @Test
    fun diskRestoreBoundsOutputKeepsRunMarkerAndRejectsOlderSnapshots() = runBlocking {
        val repository = repo()
        val session = UUID.randomUUID().toString()
        val record = AutoTaskDebugRecord("id", "old".repeat(10000) + "latest", true, true, 3)
        repository.write(session, record)
        repository.write(session, record.copy(output = "stale", revision = 2))
        val restored = repo().read(session)!!
        assertEquals(20000, restored.output.length)
        assertTrue(restored.output.endsWith("latest"))
        assertTrue(restored.hasRun)
        assertTrue(restored.running)
        try {
            repository.read("../outside")
            fail("invalid session")
        } catch (_: IllegalArgumentException) {}
        assertNull(repository.load("missing"))
    }
}
