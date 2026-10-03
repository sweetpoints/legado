package io.legado.app.data.preferences

import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.Executors

class BackupChoicesRepositoryTest {
    private class Store : BackupChoicesStore {
        val content = mutableListOf(BackupChoice("books", "Books", true), BackupChoice("cookies", "Cookies", false))
        val ignore = mutableListOf(BackupChoice("read", "Reading", false)); val calls = mutableListOf<String>(); val threads = mutableListOf<Thread>()
        private fun call(name: String) { calls += name; threads += Thread.currentThread() }
        override suspend fun load(group: BackupChoiceGroup): List<BackupChoice> { call("load:$group"); return if (group == BackupChoiceGroup.Content) content else ignore }
        override suspend fun toggle(group: BackupChoiceGroup, key: String, checked: Boolean) {
            call("toggle:$group:$key:$checked"); val rows = if (group == BackupChoiceGroup.Content) content else ignore
            val index = rows.indexOfFirst { it.key == key }; rows[index] = rows[index].copy(checked = checked)
        }
        override suspend fun save() { call("save") }
    }
    @Test fun returnedChoicesAreImmutableSnapshotsAndTogglesDoNotSaveUntilDismissal() = runBlocking {
        val store = Store(); val repo = DefaultBackupChoicesRepository(store, Dispatchers.Unconfined)
        val before = repo.load(BackupChoiceGroup.Content); val after = repo.toggle(BackupChoiceGroup.Content, "books", false)
        assertTrue(before.first().checked); assertFalse(after.first().checked); assertFalse(store.calls.contains("save"))
        repo.save(); assertEquals("save", store.calls.last())
    }
    @Test fun eachGroupKeepsOwnCheckedSenseAndUnknownKeysCannotPolluteGlobalMap() = runBlocking {
        val store = Store(); val repo = DefaultBackupChoicesRepository(store, Dispatchers.Unconfined)
        repo.toggle(BackupChoiceGroup.Content, "cookies", true); repo.toggle(BackupChoiceGroup.Ignore, "read", true)
        assertTrue(repo.load(BackupChoiceGroup.Content).last().checked); assertTrue(repo.load(BackupChoiceGroup.Ignore).single().checked)
        val calls = store.calls.count { it.startsWith("toggle:") }
        assertTrue(runCatching { repo.toggle(BackupChoiceGroup.Content, "unknown", true) }.isFailure)
        assertEquals(calls, store.calls.count { it.startsWith("toggle:") })
    }
    @Test fun allChoiceReadsMutationsAndDismissSaveUseIo() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val worker = withContext(io) { Thread.currentThread() }; val store = Store(); val repo = DefaultBackupChoicesRepository(store, io)
            repo.load(BackupChoiceGroup.Ignore); repo.toggle(BackupChoiceGroup.Ignore, "read", true); repo.save()
            store.threads.forEach { assertSame(worker, it) }
        }
    }
}
