package io.legado.app.data.repository

import io.legado.app.model.backup.BackupRestoreFiles
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackupOperationsRepositoryTest {
    private class Store : BackupOperationsStore {
        val calls = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        val names = mutableListOf("backup-new", "backup-old")
        var canWrite = false
        var truncated = false
        var gate: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null

        private fun call(name: String) {
            calls += name
            threads += Thread.currentThread()
        }

        override suspend fun writableTree(path: String): Boolean {
            call("writable:$path")
            return canWrite
        }

        override suspend fun backup(path: String?, uploadWebDav: Boolean) {
            call("backup:$path:$uploadWebDav")
            entered?.complete(Unit)
            gate?.let { withContext(NonCancellable) { it.await() } }
        }

        override suspend fun restoreFiles(): BackupRestoreFiles {
            call("list")
            return BackupRestoreFiles(names, truncated)
        }

        override suspend fun restoreWebDav(name: String) {
            call("remote:$name")
        }

        override suspend fun restoreLocal(uri: String) {
            call("local:$uri")
        }

        override suspend fun importOld(uri: String) {
            call("old:$uri")
        }
    }

    @Test
    fun manualDestinationPathAndRestoreOrImportInputsAreForwardedOnIoUnchanged() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val thread = withContext(io) { Thread.currentThread() }
            val store = Store()
            val repo = DefaultBackupOperationsRepository(store, io)
            repo.backup(null, false)
            repo.backup("content://backup", true)
            repo.restoreWebDav("backup-selected")
            repo.restoreLocal("content://selected.zip")
            repo.importOld("content://old-tree")
            assertEquals(
                listOf(
                    "backup:null:false",
                    "backup:content://backup:true",
                    "remote:backup-selected",
                    "local:content://selected.zip",
                    "old:content://old-tree",
                ),
                store.calls,
            )
            store.threads.forEach { assertSame(thread, it) }
        }
    }

    @Test
    fun returnedRestoreNamesAreIndependentAndCloudTruncationSignalIsPreserved() = runBlocking {
        val store = Store().apply { truncated = true }
        val repo = DefaultBackupOperationsRepository(store, Dispatchers.Unconfined)
        val result = repo.restoreFiles()
        store.names.clear()
        assertEquals(listOf("backup-new", "backup-old"), result.names)
        assertTrue(result.truncatedCloudListing)
        assertTrue(
            runCatching { repo.restoreFiles() }
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("no back up file")
        )
    }

    @Test
    fun treeWriteCheckHasNoBackupSideEffectAndReturnsBothStates() = runBlocking {
        val store = Store()
        val repo = DefaultBackupOperationsRepository(store, Dispatchers.Unconfined)
        assertFalse(repo.writableTree("content://tree"))
        store.canWrite = true
        assertTrue(repo.writableTree("content://tree"))
        assertEquals(listOf("writable:content://tree", "writable:content://tree"), store.calls)
    }

    @Test
    fun cancellingOwnerRejectsLateNonCooperativeBackupCompletion() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val store =
            Store().apply {
                this.gate = gate
                this.entered = entered
            }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val repo = DefaultBackupOperationsRepository(store, io)
            var published = false
            val job = launch {
                repo.backup(null, false)
                published = true
            }
            entered.await()
            job.cancel()
            gate.complete(Unit)
            job.join()
            assertFalse(published)
            assertTrue(job.isCancelled)
        }
    }
}
