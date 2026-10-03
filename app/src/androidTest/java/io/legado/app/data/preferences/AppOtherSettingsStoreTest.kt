package io.legado.app.data.preferences

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.settings.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AppOtherSettingsStoreTest {
    @Test
    fun realIsolatedPreferencesPreserveAllKeysAndTokenNeverEntersOrdinaryPreferencesOrSnapshot() =
        runBlocking {
            val context =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val name = "other-settings-test-${UUID.randomUUID()}"
            val preferences = context.getSharedPreferences(name, 0)
            val capabilities =
                object : OtherSettingsCapabilities {
                    var enabled = false

                    override fun processTextEnabled() = enabled

                    override fun processTextEnabled(value: Boolean) {
                        enabled = value
                    }

                    override fun promotedNotificationsVisible() = true
                }
            val tokens =
                object : OtherSettingsTokenStore {
                    var value: String? = null

                    override fun read() = value

                    override fun write(value: String?) {
                        this.value = value
                    }
                }
            val repository =
                DefaultOtherSettingsRepository(
                    AppOtherSettingsStore(
                        context,
                        preferences,
                        capabilities,
                        tokens,
                        { "default-UA" },
                        { "test-source-summary" },
                    )
                )
            try {
                val initial = repository.observe().first()
                assertFalse(initial.switches.getValue(OtherSwitch.ProcessText))
                assertTrue(initial.promotedNotificationsVisible)
                assertEquals("default-UA", initial.texts.getValue(OtherText.UserAgent))
                assertEquals("test-source-summary", initial.checkSourceSummary)
                OtherSwitch.entries.forEach { repository.boolean(it, !it.default) }
                repository.boolean(OtherSwitch.AutoRefresh, true)
                repository.boolean(OtherSwitch.OnlyRead, true)
                repository.boolean(OtherSwitch.ProcessText, true)
                OtherNumber.entries.forEach { repository.number(it, it.minimum) }
                repository.choice(OtherChoice.Language, "en")
                repository.choice(OtherChoice.Home, "rss")
                repository.text(OtherText.Token, " synthetic-isolated-token ")
                val snapshot = repository.load()
                assertTrue(snapshot.tokenConfigured)
                assertFalse(snapshot.toString().contains("synthetic-isolated-token"))
                assertFalse(preferences.contains(OtherText.Token.key))
                assertEquals("synthetic-isolated-token", repository.readText(OtherText.Token))
                assertTrue(capabilities.enabled)
                assertEquals("en", preferences.getString(OtherChoice.Language.key, null))
                assertEquals("rss", preferences.getString(OtherChoice.Home.key, null))
                OtherNumber.entries.forEach {
                    assertEquals(it.minimum, preferences.getInt(it.key, -1))
                }
                repository.text(OtherText.Hosts, "{\"synthetic.test\":\"127.0.0.1\"}")
                assertTrue(preferences.contains(OtherText.Hosts.key))
                repository.text(OtherText.Hosts, "not-json")
                assertFalse(preferences.contains(OtherText.Hosts.key))
                repository.text(OtherText.UserAgent, " ")
                assertFalse(preferences.contains(OtherText.UserAgent.key))
                assertFalse(preferences.contains("token"))
            } finally {
                withContext(Dispatchers.IO) {
                    preferences.edit().clear().commit()
                    context.deleteSharedPreferences(name)
                }
            }
        }
}
