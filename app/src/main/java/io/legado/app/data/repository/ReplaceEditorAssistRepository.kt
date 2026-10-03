package io.legado.app.data.repository

import android.content.SharedPreferences
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import splitties.init.appCtx

data class ReplaceEditorAssist(val key: String, val value: String)
class ReplaceEditorAssistRepository(private val database: AppDatabase = appDb) {
    fun keys(): Flow<List<ReplaceEditorAssist>> = database.keyboardAssistsDao.flowByType(0)
        .map { keys -> keys.map { ReplaceEditorAssist(it.key, it.value) } }.flowOn(Dispatchers.IO)
    fun rows(): Flow<Int> = callbackFlow {
        val preferences = appCtx.defaultSharedPreferences
        fun publish() { trySend(preferences.getInt(PreferKey.showBoardLine, 1).coerceIn(1, 5)) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == PreferKey.showBoardLine) publish() }
        preferences.registerOnSharedPreferenceChangeListener(listener); publish()
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
