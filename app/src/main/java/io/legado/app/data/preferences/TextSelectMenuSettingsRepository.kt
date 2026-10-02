package io.legado.app.data.preferences

import android.content.Context
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.ui.book.read.loadTextSelectMenuConfig
import io.legado.app.ui.book.read.saveTextSelectMenuConfig

interface TextSelectMenuSettingsRepository {
    fun load(): TextSelectMenuConfig
    fun save(config: TextSelectMenuConfig)
}
class PreferenceTextSelectMenuSettingsRepository(context: Context) : TextSelectMenuSettingsRepository {
    private val context = context.applicationContext
    override fun load() = loadTextSelectMenuConfig(context)
    override fun save(config: TextSelectMenuConfig) = saveTextSelectMenuConfig(context, config)
}
