package io.legado.app.data.repository

import android.content.SharedPreferences
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefInt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn

data class RssSourceEditorAssist(val key: String, val value: String)
data class RssSourceEditorAssistPreferences(val rows: Int, val maxLines: Int)
class RssSourceEditorAssistRepository(private val database: AppDatabase = appDb) {
    fun keys(): Flow<List<RssSourceEditorAssist>> = database.keyboardAssistsDao.flowByType(0)
        .map { values -> values.map { RssSourceEditorAssist(it.key, it.value) } }.flowOn(Dispatchers.IO)
    fun preferences(): Flow<RssSourceEditorAssistPreferences> = callbackFlow {
        val preferences = splitties.init.appCtx.defaultSharedPreferences
        fun publish() { trySend(RssSourceEditorAssistPreferences(rows, maxLines)) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == PreferKey.showBoardLine || key == PreferKey.sourceEditMaxLine) publish() }
        preferences.registerOnSharedPreferenceChangeListener(listener); publish()
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    val rows get() = splitties.init.appCtx.getPrefInt(PreferKey.showBoardLine, 1).coerceIn(1, 5)
    val maxLines get() = AppConfig.sourceEditMaxLine.coerceAtLeast(1)
}
