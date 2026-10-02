package io.legado.app.data.preferences

import android.content.Context
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.ui.book.read.loadReaderMenuConfig
import io.legado.app.ui.book.read.saveReaderMenuConfig

interface ReaderMenuSettingsRepository {
    fun load(): ReaderMenuConfig
    fun save(config: ReaderMenuConfig)
}

class PreferenceReaderMenuSettingsRepository(context: Context) : ReaderMenuSettingsRepository {
    private val context = context.applicationContext
    override fun load() = loadReaderMenuConfig(context).normalized()
    override fun save(config: ReaderMenuConfig) = saveReaderMenuConfig(context, config)
}
