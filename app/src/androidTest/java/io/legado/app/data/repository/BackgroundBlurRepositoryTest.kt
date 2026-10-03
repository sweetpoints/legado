package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackgroundBlurRepositoryTest {
    @Test
    fun commitsOnlyRequestedThemeAndClampsToLegacySliderRange() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.defaultSharedPreferences
        val keys = listOf(PreferKey.bgImageBlurring, PreferKey.bgImageNBlurring)
        val previous = keys.associateWith {
            if (preferences.contains(it)) preferences.getInt(it, 0) else null
        }
        try {
            val repository = PreferencesBackgroundBlurRepository(context)
            repository.save(false, 8)
            repository.save(true, 40)
            assertEquals(8, repository.load(false))
            assertEquals(25, repository.load(true))
            repository.save(true, 0)
            assertEquals(0, repository.load(true))
            assertEquals(8, repository.load(false))
        } finally {
            val editor = preferences.edit()
            previous.forEach { (key, value) ->
                if (value == null) editor.remove(key) else editor.putInt(key, value)
            }
            assertTrue(editor.commit())
        }
    }
}
