package io.legado.app.data.preferences

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CheckSource
import io.legado.app.model.settings.*
import io.legado.app.receiver.SharedReceiverActivity
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.supportsPromotedNotifications
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

internal interface OtherSettingsCapabilities {
    fun processTextEnabled(): Boolean
    fun processTextEnabled(value: Boolean)
    fun promotedNotificationsVisible(): Boolean
}
internal interface OtherSettingsTokenStore { fun read(): String?; fun write(value: String?) }
internal class AppOtherSettingsTokenStore : OtherSettingsTokenStore {
    override fun read() = AppConfig.jsSourceApiToken
    override fun write(value: String?) { AppConfig.jsSourceApiToken = value }
}
internal class AppOtherSettingsCapabilities(context: Context) : OtherSettingsCapabilities {
    private val application = context.applicationContext
    private val component = ComponentName(application, SharedReceiverActivity::class.java.name)
    override fun processTextEnabled() = application.packageManager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    override fun processTextEnabled(value: Boolean) { application.packageManager.setComponentEnabledSetting(component,
        if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
    override fun promotedNotificationsVisible(): Boolean {
        if (!supportsPromotedNotifications()) return false
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, application.packageName)
        return NotificationManagerCompat.from(application).canPostPromotedNotifications() || intent.resolveActivity(application.packageManager) != null
    }
}
/** Application context only; the repository owns its IO dispatcher and accepted-write boundary. */
internal class AppOtherSettingsStore(context: Context, preferences: SharedPreferences? = null,
    private val capabilities: OtherSettingsCapabilities = AppOtherSettingsCapabilities(context),
    private val tokens: OtherSettingsTokenStore = AppOtherSettingsTokenStore(),
    private val userAgent: () -> String = { AppConfig.userAgent }, private val sourceSummary: () -> String = { CheckSource.summary }) : OtherSettingsStore {
    private val application = context.applicationContext
    private val preferences by lazy { preferences ?: application.defaultSharedPreferences }
    private val explicitChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override fun changes(): Flow<Unit> = merge(callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener); trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }, explicitChanges)
    override suspend fun initializeProcessText() { save(preferences.edit().putBoolean(OtherSwitch.ProcessText.key, capabilities.processTextEnabled())) }
    override suspend fun load(): OtherSettingsSnapshot {
        val values = preferences.all
        return OtherSettingsSnapshot(OtherSwitch.entries.associateWith { values[it.key] as? Boolean ?: it.default },
            OtherNumber.entries.associateWith { key -> (values[key.key] as? Int ?: key.default).let { if (key == OtherNumber.SourceLines && it < 10) Int.MAX_VALUE else it } },
            OtherText.entries.filter { it != OtherText.Token }.associateWith { key -> when (key) {
                OtherText.UserAgent -> (values[key.key] as? String)?.takeIf { it.isNotBlank() } ?: userAgent()
                else -> values[key.key] as? String ?: ""
            } }, OtherChoice.entries.associateWith { values[it.key] as? String ?: it.default },
            !tokens.read().isNullOrBlank(), capabilities.promotedNotificationsVisible(), sourceSummary())
    }
    override suspend fun readText(key: OtherText): String = when (key) {
        OtherText.Token -> tokens.read().orEmpty()
        OtherText.UserAgent -> preferences.getString(key.key, null)?.takeIf { it.isNotBlank() } ?: userAgent()
        else -> preferences.getString(key.key, null).orEmpty()
    }
    private fun save(editor: SharedPreferences.Editor) { check(editor.commit()) { "Unable to save other settings" } }
    override suspend fun boolean(key: OtherSwitch, value: Boolean) {
        save(preferences.edit().putBoolean(key.key, value))
        if (key == OtherSwitch.ProcessText) capabilities.processTextEnabled(value)
    }
    override suspend fun number(key: OtherNumber, value: Int) = save(preferences.edit().putInt(key.key, value))
    override suspend fun text(key: OtherText, value: String?) {
        if (key == OtherText.Token) { tokens.write(value); explicitChanges.tryEmit(Unit) }
        else save(preferences.edit().apply { if (value == null) remove(key.key) else putString(key.key, value) })
    }
    override suspend fun choice(key: OtherChoice, value: String) = save(preferences.edit().putString(key.key, value))
}
