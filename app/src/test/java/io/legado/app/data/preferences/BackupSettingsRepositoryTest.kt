package io.legado.app.data.preferences

import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.Executors

class BackupSettingsRepositoryTest {
    private class Store : BackupSettingsStore {
        var value = BackupSettingsSnapshot(); val calls = mutableListOf<String>(); val threads = mutableListOf<Thread>(); var failWrite = false; var afterText: (suspend () -> Unit)? = null
        fun call(name: String) { calls += name; threads += Thread.currentThread() }
        override fun changes(): Flow<Unit> = flowOf(Unit)
        override suspend fun load(): BackupSettingsSnapshot { call("load"); return value }
        override suspend fun text(key: BackupSettingText, value: String) { call("text:${key.name}"); if (failWrite) error("write failed"); this.value = this.value.copy(texts = this.value.texts + (key to value)); afterText?.invoke() }
        override suspend fun boolean(key: BackupSettingSwitch, value: Boolean) { call("bool:${key.name}:$value"); this.value = this.value.copy(switches = this.value.switches + (key to value)) }
        override suspend fun automatic(value: AutoBackupSettings) { call("automatic"); this.value = this.value.copy(automatic = value) }
        override suspend fun path(value: String?) { call("path"); this.value = this.value.copy(backupPath = value) }
        override suspend fun localPassword(value: String) { call("local-password") }
        override suspend fun reconfigureWebDav() { call("reconfigure") }
        override suspend fun needsHelp(): Boolean { call("help"); return true }
    }
    @Test fun changingWebDavConnectionFieldsReconfiguresAfterSaveWhileDeviceDoesNot() = runBlocking {
        val store = Store(); val repo = DefaultBackupSettingsRepository(store, Dispatchers.Unconfined)
        listOf(BackupSettingText.Url, BackupSettingText.Account, BackupSettingText.Password, BackupSettingText.Directory).forEach { key ->
            store.calls.clear(); repo.text(key, "draft"); assertEquals(listOf("text:${key.name}", "reconfigure"), store.calls) }
        store.calls.clear(); repo.text(BackupSettingText.Device, "device"); assertEquals(listOf("text:Device"), store.calls)
        store.failWrite = true; store.calls.clear(); assertTrue(runCatching { repo.text(BackupSettingText.Url, "new") }.isFailure)
        assertEquals(listOf("text:Url"), store.calls)
    }
    @Test fun acceptedConnectionSaveStillReconfiguresWhenCallerIsCanceledDuringIoCommit() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val store = Store(); store.afterText = { entered.complete(Unit); finish.await() }
        val repo = DefaultBackupSettingsRepository(store, Dispatchers.IO)
        val job = launch { repo.text(BackupSettingText.Url, "fixture") }
        try {
            entered.await(); job.cancel(); finish.complete(Unit); job.join()
            assertTrue(job.isCancelled); assertEquals(listOf("text:Url", "reconfigure"), store.calls)
            assertEquals("fixture", store.value.texts.getValue(BackupSettingText.Url))
        } finally { finish.complete(Unit); job.cancelAndJoin() }
    }
    @Test fun progressPlusDependencyDisablesChangesWithoutResettingPreviouslyStoredValue() = runBlocking {
        val store = Store(); val repo = DefaultBackupSettingsRepository(store, Dispatchers.Unconfined)
        repo.boolean(BackupSettingSwitch.ProgressPlus, true); repo.boolean(BackupSettingSwitch.Progress, false)
        store.calls.clear(); repo.boolean(BackupSettingSwitch.ProgressPlus, false)
        assertEquals(listOf("load"), store.calls); assertTrue(store.value.switches.getValue(BackupSettingSwitch.ProgressPlus))
        assertFalse(store.value.enabled(BackupSettingSwitch.ProgressPlus)); assertTrue(store.value.enabled(BackupSettingSwitch.Latest))
    }
    @Test fun autoBackupIsOneValidatedThreeFieldWriteWithNoChangesForInvalidDays() = runBlocking {
        val store = Store(); val repo = DefaultBackupSettingsRepository(store, Dispatchers.Unconfined)
        assertTrue(runCatching { repo.automatic(AutoBackupSettings(false, false, 0)) }.isFailure); assertTrue(store.calls.isEmpty())
        val wanted = AutoBackupSettings(false, false, 37); repo.automatic(wanted)
        assertEquals(listOf("automatic"), store.calls); assertEquals(wanted, store.value.automatic)
    }
    @Test fun defaultAndEmptyBackupPathClearOnlyThePathAndKeepManualRemoteSettings() = runBlocking {
        val store = Store(); val repo = DefaultBackupSettingsRepository(store, Dispatchers.Unconfined)
        repo.path("content://selected-tree"); assertEquals("content://selected-tree", store.value.backupPath)
        repo.path(""); assertNull(store.value.backupPath); repo.path("/selected-directory"); repo.path(null); assertNull(store.value.backupPath)
        assertEquals(AutoBackupSettings(), store.value.automatic); assertEquals("legado", store.value.texts.getValue(BackupSettingText.Directory))
    }
    @Test fun allReadsWritesAuthorizationAndFirstHelpChecksUseIoWithoutLoggingInputPayloads() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val ioThread = withContext(io) { Thread.currentThread() }; val store = Store(); val repo = DefaultBackupSettingsRepository(store, io)
            repo.observe().first(); repo.load(); repo.text(BackupSettingText.Account, "fixture"); repo.boolean(BackupSettingSwitch.Latest, false)
            repo.automatic(AutoBackupSettings(true, false, 3)); repo.path(null); repo.localPassword("fixture"); assertTrue(repo.needsHelp())
            assertTrue(store.threads.isNotEmpty()); store.threads.forEach { assertSame(ioThread, it) }
        }
    }
}
