package io.legado.app.data.preferences

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

interface TextSelectMenuSettingsRepository {
    fun load(): TextSelectMenuConfig

    fun save(config: TextSelectMenuConfig)
}

class PreferenceTextSelectMenuSettingsRepository(context: Context) :
    TextSelectMenuSettingsRepository {
    private val context = context.applicationContext

    override fun load() = loadTextSelectMenuConfig(context)

    override fun save(config: TextSelectMenuConfig) = saveTextSelectMenuConfig(context, config)
}

internal fun loadTextSelectMenuConfig(context: Context): TextSelectMenuConfig {
    val json = context.getPrefString(PreferKey.textSelectMenuConfig)
    if (json.isNullOrBlank()) {
        val migrated =
            TextSelectMenuConfig.migrateFrom(context.getPrefBoolean(PreferKey.expandTextMenu))
        context.putPrefString(PreferKey.textSelectMenuConfig, migrated.toJson())
        return migrated.normalized()
    }
    return TextSelectMenuConfig.fromJson(json).normalized()
}

internal fun saveTextSelectMenuConfig(context: Context, config: TextSelectMenuConfig) {
    context.putPrefString(PreferKey.textSelectMenuConfig, config.normalized().toJson())
}
