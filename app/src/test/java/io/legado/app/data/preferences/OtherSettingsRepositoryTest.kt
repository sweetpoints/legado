package io.legado.app.data.preferences

import io.legado.app.model.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.Executors

class OtherSettingsRepositoryTest {
    private class Store : OtherSettingsStore {
        var snapshot = OtherSettingsSnapshot(); val writes = mutableListOf<String>(); var token = ""; val threads = mutableListOf<Thread>()
        private fun called() { threads += Thread.currentThread() }
        override fun changes(): Flow<Unit> = flowOf(Unit)
        override suspend fun initializeProcessText() { called() }
        override suspend fun load(): OtherSettingsSnapshot { called(); return snapshot }
        override suspend fun readText(key: OtherText): String { called(); return if (key == OtherText.Token) token else snapshot.texts.getValue(key) }
        override suspend fun boolean(key: OtherSwitch, value: Boolean) { called(); writes += "boolean:${key.name}:$value"; snapshot = snapshot.copy(switches = snapshot.switches + (key to value)) }
        override suspend fun number(key: OtherNumber, value: Int) { called(); writes += "number:${key.name}:$value"; snapshot = snapshot.copy(numbers = snapshot.numbers + (key to value)) }
        override suspend fun text(key: OtherText, value: String?) { called(); writes += "text:${key.name}"; if (key == OtherText.Token) { token = value.orEmpty(); snapshot = snapshot.copy(tokenConfigured = token.isNotEmpty()) }
            else snapshot = snapshot.copy(texts = snapshot.texts + (key to value.orEmpty())) }
        override suspend fun choice(key: OtherChoice, value: String) { called(); writes += "choice:${key.name}:$value"; snapshot = snapshot.copy(choices = snapshot.choices + (key to value)) }
    }
    private fun test(block: suspend (Store, OtherSettingsRepository) -> Unit) = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val store = Store(); val caller = Thread.currentThread(); block(store, DefaultOtherSettingsRepository(store, io))
            assertTrue(store.threads.isNotEmpty()); assertTrue(store.threads.all { it !== caller })
        }
    }
    @Test fun defaultsVisibilityAndProcessInitializationDoNotRunMutationsOrEffects() = test { store, repo ->
        val value = repo.observe().first(); assertEquals(23, value.switches.size); assertEquals(7, value.numbers.size)
        assertFalse(value.visible(OtherSwitch.OnlyRead)); assertFalse(value.visible(OtherSwitch.LiveNotifications))
        assertTrue(value.switches.getValue(OtherSwitch.ProcessText)); assertTrue(store.writes.isEmpty())
        assertFalse(value.texts.containsKey(OtherText.Token)); assertFalse(value.tokenConfigured)
    }
    @Test fun numbersClampToOriginalPickerBoundsAndReturnOnlyTheirPlatformEffects() = test { store, repo ->
        assertEquals(listOf(OtherEffect.RestartWeb), repo.number(OtherNumber.WebPort, 0)); assertEquals(1024, store.snapshot.numbers.getValue(OtherNumber.WebPort))
        assertEquals(listOf(OtherEffect.RestartMcp), repo.number(OtherNumber.McpPort, Int.MAX_VALUE)); assertEquals(65530, store.snapshot.numbers.getValue(OtherNumber.McpPort))
        assertEquals(listOf(OtherEffect.ThreadsChanged), repo.number(OtherNumber.Threads, 1000)); assertEquals(999, store.snapshot.numbers.getValue(OtherNumber.Threads))
        repo.number(OtherNumber.PreDownload, -1); assertEquals(0, store.snapshot.numbers.getValue(OtherNumber.PreDownload))
        repo.number(OtherNumber.SourceLines, 1); assertEquals(10, store.snapshot.numbers.getValue(OtherNumber.SourceLines))
    }
    @Test fun hiddenControlsCannotWriteAndSwitchEffectsFollowAcceptedValue() = test { store, repo ->
        repo.boolean(OtherSwitch.OnlyRead, true); repo.boolean(OtherSwitch.LiveNotifications, true); assertTrue(store.writes.isEmpty())
        repo.boolean(OtherSwitch.AutoRefresh, true); repo.boolean(OtherSwitch.OnlyRead, true); assertTrue(store.snapshot.switches.getValue(OtherSwitch.OnlyRead))
        assertEquals(listOf(OtherEffect.RestartWeb, OtherEffect.RestartMcp), repo.boolean(OtherSwitch.TokenRequired, false))
        assertEquals(listOf(OtherEffect.DownloadCronet), repo.boolean(OtherSwitch.Cronet, true)); assertTrue(repo.boolean(OtherSwitch.Cronet, false).isEmpty())
        assertEquals(listOf(OtherEffect.NotifyMain), repo.boolean(OtherSwitch.Discovery, false)); assertEquals(listOf(OtherEffect.LogConfiguration), repo.boolean(OtherSwitch.Log, true))
    }
    @Test fun tokenIsNormalizedAndItsValueNeverAppearsInSnapshotOrEffect() = test { store, repo ->
        assertEquals(listOf(OtherEffect.RestartMcp), repo.text(OtherText.Token, "  synthetic-token  ")); assertEquals("synthetic-token", repo.readText(OtherText.Token))
        assertTrue(store.snapshot.tokenConfigured); assertFalse(store.snapshot.toString().contains("synthetic-token")); assertFalse(store.snapshot.texts.containsKey(OtherText.Token))
        assertTrue(repo.text(OtherText.Token, "synthetic-token").isEmpty()); assertEquals(listOf(OtherEffect.StopMcp), repo.text(OtherText.Token, "   "))
        repo.boolean(OtherSwitch.TokenRequired, false); assertTrue(repo.text(OtherText.Token, "another-synthetic").isEmpty())
    }
    @Test fun userAgentBlankAndInvalidJsonClearWhileValidJsonRetainsItsExactText() = test { store, repo ->
        repo.text(OtherText.UserAgent, " synthetic-UA "); assertEquals(" synthetic-UA ", store.snapshot.texts.getValue(OtherText.UserAgent))
        repo.text(OtherText.UserAgent, "  "); assertEquals("", store.snapshot.texts.getValue(OtherText.UserAgent))
        val hosts = "{\"example.test\":\"127.0.0.1\"}"; repo.text(OtherText.Hosts, hosts); assertEquals(hosts, store.snapshot.texts.getValue(OtherText.Hosts))
        repo.text(OtherText.Hosts, "[]"); assertEquals("", store.snapshot.texts.getValue(OtherText.Hosts)); repo.text(OtherText.Hosts, "unfinished {"); assertEquals("", store.snapshot.texts.getValue(OtherText.Hosts))
    }
    @Test fun onlyLanguageChangeRequestsRestartAndUnchangedValuesHaveNoDuplicateEffects() = test { store, repo ->
        assertEquals(listOf(OtherEffect.RestartApplication), repo.choice(OtherChoice.Language, "en")); assertTrue(repo.choice(OtherChoice.Language, "en").isEmpty())
        assertTrue(repo.choice(OtherChoice.Home, "rss").isEmpty()); assertEquals("rss", store.snapshot.choices.getValue(OtherChoice.Home))
        val count = store.writes.size; repo.number(OtherNumber.BitmapCache, 50); assertEquals(count, store.writes.size)
        assertEquals(listOf(OtherEffect.ResizeBitmapCache), repo.number(OtherNumber.BitmapCache, 51))
    }
    @Test fun processReconciliationReadsLatestPreferenceInsideAcceptedGateAndInvalidPromotionCanRollBackHiddenFlag() = test { store, repo ->
        repo.boolean(OtherSwitch.ProcessText, false)
        store.snapshot = store.snapshot.copy(switches = store.snapshot.switches + (OtherSwitch.ProcessText to true) + (OtherSwitch.LiveNotifications to true))
        repo.reconcileProcessText(); assertEquals("boolean:ProcessText:true", store.writes.last())
        assertTrue(repo.boolean(OtherSwitch.LiveNotifications, false).isEmpty()); assertFalse(store.snapshot.switches.getValue(OtherSwitch.LiveNotifications))
        assertFalse(store.snapshot.promotedNotificationsVisible)
    }

}
