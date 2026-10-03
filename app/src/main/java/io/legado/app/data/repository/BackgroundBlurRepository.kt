package io.legado.app.data.repository

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface BackgroundBlurRepository {
    suspend fun load(night: Boolean): Int

    suspend fun save(night: Boolean, radius: Int)
}

class PreferencesBackgroundBlurRepository(context: Context) : BackgroundBlurRepository {
    private val preferences = context.applicationContext.defaultSharedPreferences

    private fun key(night: Boolean) =
        if (night) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring

    override suspend fun load(night: Boolean) =
        withContext(Dispatchers.IO) {
            preferences.getInt(key(night), 0).coerceIn(0, 25)
        }

    override suspend fun save(night: Boolean, radius: Int): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            check(preferences.edit().putInt(key(night), radius.coerceIn(0, 25)).commit()) {
                "Unable to save background blur"
            }
        }
}
