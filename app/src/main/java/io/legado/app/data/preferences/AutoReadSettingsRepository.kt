package io.legado.app.data.preferences

import io.legado.app.help.config.ReadBookConfig

interface AutoReadSettingsRepository {
    fun readSpeed(): Int

    fun saveSpeed(speed: Int)
}

class PreferenceAutoReadSettingsRepository : AutoReadSettingsRepository {
    override fun readSpeed() = ReadBookConfig.autoReadSpeed

    // ReadBookConfig delegates persistence to SharedPreferences.apply.
    override fun saveSpeed(speed: Int) {
        ReadBookConfig.autoReadSpeed = speed
    }
}
