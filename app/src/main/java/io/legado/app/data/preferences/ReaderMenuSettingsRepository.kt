package io.legado.app.data.preferences

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

interface ReaderMenuSettingsRepository {
    fun load(): ReaderMenuConfig

    fun save(config: ReaderMenuConfig)
}

class PreferenceReaderMenuSettingsRepository(context: Context) : ReaderMenuSettingsRepository {
    private val context = context.applicationContext

    override fun load() = loadReaderMenuConfig(context).normalized()

    override fun save(config: ReaderMenuConfig) = saveReaderMenuConfig(context, config)
}

internal fun loadReaderMenuConfig(context: Context): ReaderMenuConfig {
    val json = context.getPrefString(PreferKey.readerMenuConfig)
    if (json.isNullOrBlank()) {
        val config = ReaderMenuConfig.default()
        saveReaderMenuConfig(context, config)
        return config
    }
    return ReaderMenuConfig.fromJson(json).normalized()
}

internal fun saveReaderMenuConfig(context: Context, config: ReaderMenuConfig) {
    context.putPrefString(PreferKey.readerMenuConfig, config.normalized().toJson())
}
