package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import io.legado.app.constant.PreferKey
import io.legado.app.model.webBook.BookSearchPreferences
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal class AppBookSearchPreferencesStore(
    context: Context,
    private val ownedPreferences: SharedPreferences? = null,
) : BookSearchPreferencesStore {
    private val application = context.applicationContext
    private val preferences by lazy { ownedPreferences ?: application.defaultSharedPreferences }

    override fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in observedKeys) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override suspend fun load(): BookSearchPreferences {
        return BookSearchPreferences(
            precision = preferences.getBoolean(PreferKey.precisionSearch, false),
            showReadRecord = preferences.getBoolean(PreferKey.showSearchReadRecord, true),
            resultFilter = preferences.getString(PreferKey.searchResultFilter, "").orEmpty(),
            scope = preferences.getString(scopeKey, "").orEmpty(),
            loadCoverOnlyWifi = preferences.getBoolean(PreferKey.loadCoverOnlyWifi, false),
        )
    }

    override suspend fun precision(value: Boolean) {
        save(preferences.edit().putBoolean(PreferKey.precisionSearch, value))
    }

    override suspend fun showReadRecord(value: Boolean) {
        save(preferences.edit().putBoolean(PreferKey.showSearchReadRecord, value))
    }

    override suspend fun resultFilter(value: String) {
        save(preferences.edit().putString(PreferKey.searchResultFilter, value))
    }

    override suspend fun scope(value: String) {
        val group =
            if (value.isEmpty() || value.contains("::") || value.contains(",")) "" else value
        // Scope and its legacy single-group companion must describe the same accepted choice.
        save(preferences.edit().putString(scopeKey, value).putString("searchGroup", group))
    }

    private fun save(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Unable to save book search preferences" }
    }

    private companion object {
        const val scopeKey = "searchScope"
        val observedKeys =
            setOf(
                PreferKey.precisionSearch,
                PreferKey.showSearchReadRecord,
                PreferKey.searchResultFilter,
                PreferKey.loadCoverOnlyWifi,
                scopeKey,
            )
    }
}
